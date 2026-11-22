package com.example.bank.support;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/** 銀行営業日（土日と HOLIDAY 表の祝日・年末年始を除く）。 */
public final class BusinessCalendar {
    private BusinessCalendar() {}

    /**
     * 営業日かどうかを判定する。
     *
     * @param c    DB 接続（HOLIDAY 表の参照に使う）
     * @param date 判定する日
     * @return 土日でも HOLIDAY 表の休日でもなければ true
     * @throws SQLException DB アクセスに失敗した場合
     */
    public static boolean isBusinessDay(Connection c, LocalDate date) throws SQLException {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM holiday WHERE holiday_date = ?")) {
            ps.setDate(1, Date.valueOf(date));
            try (ResultSet rs = ps.executeQuery()) {
                return !rs.next();
            }
        }
    }

    /** date が営業日ならそのまま、そうでなければ翌営業日。 */
    public static LocalDate onOrAfter(Connection c, LocalDate date) throws SQLException {
        LocalDate d = date;
        // 年末年始などの連休でも、営業日に当たるまで 1 日ずつ進める
        while (!isBusinessDay(c, d)) d = d.plusDays(1);
        return d;
    }

    /**
     * 期間の休日をまとめて読み込んだ営業日カレンダー（返済予定表のように多数の日付を判定するとき用）。
     * 1 日ごとに HOLIDAY 表を照会する {@link #onOrAfter(Connection, LocalDate)} と同じ結果を、1 回の照会で返す。
     * 読み込んだ範囲を超える日付が来たら、その先を追加で読み込む。
     */
    public static final class Holidays {
        private final Connection c;
        private final Set<LocalDate> days = new HashSet<>();
        private LocalDate loadedTo;

        private Holidays(Connection c) { this.c = c; }

        /**
         * @param c    DB 接続
         * @param from 判定する最初の日
         * @param to   判定する最後の日（目安。超えた分は必要になったときに読み込む）
         * @return 読み込み済みのカレンダー
         * @throws SQLException DB アクセスに失敗した場合
         */
        public static Holidays load(Connection c, LocalDate from, LocalDate to) throws SQLException {
            Holidays h = new Holidays(c);
            h.fetch(from, to.plusDays(14));   // 連休で返済日が後ろにずれる分の余裕
            return h;
        }

        private void fetch(LocalDate from, LocalDate to) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT holiday_date FROM holiday WHERE holiday_date >= ? AND holiday_date <= ?")) {
                ps.setDate(1, Date.valueOf(from));
                ps.setDate(2, Date.valueOf(to));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) days.add(rs.getDate(1).toLocalDate());
                }
            }
            loadedTo = to;
        }

        /** 営業日か（土日・読み込んだ休日を除く）。 */
        public boolean isBusinessDay(LocalDate date) throws SQLException {
            if (date.isAfter(loadedTo)) fetch(loadedTo.plusDays(1), date.plusYears(1));
            DayOfWeek dow = date.getDayOfWeek();
            return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !days.contains(date);
        }

        /** date が営業日ならそのまま、そうでなければ翌営業日。 */
        public LocalDate onOrAfter(LocalDate date) throws SQLException {
            LocalDate d = date;
            while (!isBusinessDay(d)) d = d.plusDays(1);
            return d;
        }
    }

    /** date の翌営業日。 */
    public static LocalDate next(Connection c, LocalDate date) throws SQLException {
        // 振込予約・口座振替の実行日など「翌営業日」が必要な処理で使う
        return onOrAfter(c, date.plusDays(1));
    }
}
