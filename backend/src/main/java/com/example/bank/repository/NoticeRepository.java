package com.example.bank.repository;

import com.example.bank.support.Timestamps;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** WITHDRAWAL_NOTICE（通知預金の払出し通知）。 */
public class NoticeRepository {

    /**
     * 払出し通知を登録する。
     *
     * @param c             DB 接続
     * @param accountNo     口座番号
     * @param amount        払い出す予定の金額
     * @param now           通知日時
     * @param availableFrom 払出し可能になる日
     * @return 通知 ID
     * @throws SQLException DB アクセスに失敗した場合
     */
    public long insert(Connection c, String accountNo, BigDecimal amount, LocalDateTime now, LocalDate availableFrom)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO withdrawal_notice (account_no, amount, notified_at, "
                + "available_from, status) VALUES (?, ?, ?, ?, 'OPEN')", new String[]{"notice_id"})) {
            ps.setString(1, accountNo);
            ps.setBigDecimal(2, amount);
            ps.setTimestamp(3, Timestamp.valueOf(now));
            ps.setDate(4, Date.valueOf(availableFrom));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * 払出可能日を過ぎた、金額以上の通知を 1 件消し込む（通知金額の小さい順）。
     *
     * @return false: 使える通知がない
     */
    public boolean consume(Connection c, String accountNo, BigDecimal amount, LocalDate today) throws SQLException {
        Long id = null;
        try (PreparedStatement ps = c.prepareStatement("SELECT notice_id FROM withdrawal_notice WHERE account_no = ? "
                // 金額の小さい通知から使い、大きな通知を後の払出しのために残す（呼び出し側は口座行をロック済みなので同じ通知を二重に使わない）
                + "AND status = 'OPEN' AND available_from <= ? AND amount >= ? ORDER BY amount, notice_id "
                + "FETCH FIRST 1 ROWS ONLY")) {
            ps.setString(1, accountNo);
            ps.setDate(2, Date.valueOf(today));
            ps.setBigDecimal(3, amount);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) id = rs.getLong(1);
            }
        }
        if (id == null) return false;
        return setStatus(c, id, "USED") == 1;
    }

    /**
     * 未使用の払出し通知を取り消す。
     *
     * @param c         DB 接続
     * @param accountNo 口座番号（他の口座の通知を取り消せないよう条件に含める）
     * @param noticeId  通知 ID
     * @return 取り消した件数（0 なら対象なし・使用済み）
     * @throws SQLException DB アクセスに失敗した場合
     */
    public int cancel(Connection c, String accountNo, long noticeId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE withdrawal_notice SET status = 'CANCELLED' "
                + "WHERE notice_id = ? AND account_no = ? AND status = 'OPEN'")) {
            ps.setLong(1, noticeId);
            ps.setString(2, accountNo);
            return ps.executeUpdate();
        }
    }

    /**
     * 口座の払出し通知を返す。
     *
     * @param c         DB 接続
     * @param accountNo 口座番号
     * @return 通知一覧
     * @throws SQLException DB アクセスに失敗した場合
     */
    public List<Map<String, Object>> list(Connection c, String accountNo) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT notice_id, amount, notified_at, available_from, status "
                + "FROM withdrawal_notice WHERE account_no = ? ORDER BY notice_id DESC FETCH FIRST 50 ROWS ONLY")) {
            ps.setString(1, accountNo);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> list = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("noticeId", rs.getLong(1));
                    m.put("amount", rs.getBigDecimal(2));
                    m.put("notifiedAt", Timestamps.format(rs.getTimestamp(3)));
                    m.put("availableFrom", rs.getDate(4).toLocalDate().toString());
                    m.put("status", rs.getString(5));
                    list.add(m);
                }
                return list;
            }
        }
    }

    // 通知の状態（OPEN → USED / CANCELLED）を更新する
    private static int setStatus(Connection c, long id, String status) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE withdrawal_notice SET status = ? WHERE notice_id = ?")) {
            ps.setString(1, status);
            ps.setLong(2, id);
            return ps.executeUpdate();
        }
    }
}
