package com.example.bank.repository;

import com.example.bank.service.InterestCalculator.Calculation;
import com.example.bank.service.InterestCalculator.Rate;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** INTEREST_RATE（金利）と INTEREST_SETTLEMENT（利息決算）への JDBC アクセス。 */
public class InterestRepository {

    /** 種別の金利（適用開始日の昇順）。 */
    public List<Rate> rates(Connection c, String accountType) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT effective_from, annual_rate FROM interest_rate WHERE account_type = ? ORDER BY effective_from")) {
            ps.setString(1, accountType);
            try (ResultSet rs = ps.executeQuery()) {
                List<Rate> list = new ArrayList<>();
                while (rs.next()) list.add(new Rate(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2)));
                return list;
            }
        }
    }

    /** 最後に決算した期間の末日（未決算なら null）。 */
    public LocalDate lastSettledTo(Connection c, String accountNo) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT MAX(period_to) FROM interest_settlement WHERE account_no = ?")) {
            ps.setString(1, accountNo);
            try (ResultSet rs = ps.executeQuery()) {
                Date d = rs.next() ? rs.getDate(1) : null;
                return d == null ? null : d.toLocalDate();
            }
        }
    }

    /**
     * 利息決算の結果を記録する（次回の決算は記録した期間の翌日から計算する）。
     *
     * @param c         DB 接続
     * @param accountNo 口座番号
     * @param from      計算期間の初日
     * @param to        計算期間の末日
     * @param calc      計算結果
     * @param now       決算日時
     * @throws SQLException DB アクセスに失敗した場合（同じ期間の二重決算は一意制約で失敗する）
     */
    public void insertSettlement(Connection c, String accountNo, LocalDate from, LocalDate to, Calculation calc,
                                 LocalDateTime now) throws SQLException {
        String sql = "INSERT INTO interest_settlement (account_no, period_from, period_to, days, balance_days, "
                   + "gross_interest, national_tax, local_tax, net_interest, settled_at) "
                   + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, accountNo);
            ps.setDate(2, Date.valueOf(from));
            ps.setDate(3, Date.valueOf(to));
            ps.setInt(4, calc.days);
            ps.setBigDecimal(5, calc.balanceDays);
            ps.setBigDecimal(6, calc.grossInterest);
            ps.setBigDecimal(7, calc.nationalTax);
            ps.setBigDecimal(8, calc.localTax);
            ps.setBigDecimal(9, calc.netInterest());
            ps.setTimestamp(10, Timestamp.valueOf(now));
            ps.executeUpdate();
        }
    }
}
