package com.example.bank.repository;

import com.example.bank.domain.AccountTransaction;
import com.example.bank.domain.TxType;
import com.example.bank.service.InterestCalculator.BalanceChange;
import com.example.bank.support.Timestamps;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** ACCOUNT_TRANSACTION への JDBC アクセス。 */
public class TransactionRepository {

    private static final String INSERT = "INSERT INTO account_transaction "
            + "(account_no, tx_type, amount, balance_after, counterparty_account_no, memo, idempotency_key, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String COLS =
            "tx_id, account_no, tx_type, amount, balance_after, counterparty_account_no, memo, created_at";
    /** 残高が増える取引種別（集計用。TxType.isCredit と一致させる） */
    private static final String CREDIT_TYPES = "('DEPOSIT', 'TRANSFER_IN', 'INTEREST')";

    /** 取引履歴の検索条件。from は含む、toExclusive は含まない。null は条件なし。 */
    public static final class Filter {
        final String accountNo;
        final LocalDateTime from;
        final LocalDateTime toExclusive;
        final List<TxType> types;

        public Filter(String accountNo, LocalDateTime from, LocalDateTime toExclusive, List<TxType> types) {
            this.accountNo = accountNo;
            this.from = from;
            this.toExclusive = toExclusive;
            this.types = types == null ? Collections.<TxType>emptyList() : types;
        }
    }

    /** 件数と入出金合計。 */
    public static final class Totals {
        public final long count;
        public final BigDecimal totalIn;
        public final BigDecimal totalOut;

        Totals(long count, BigDecimal totalIn, BigDecimal totalOut) {
            this.count = count;
            this.totalIn = totalIn;
            this.totalOut = totalOut;
        }
    }

    public void insert(Connection c, String accountNo, TxType type, BigDecimal amount, BigDecimal balanceAfter,
                       String counterpartyAccountNo, String memo, String idempotencyKey, LocalDateTime now)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(INSERT)) {
            bind(ps, accountNo, type, amount, balanceAfter, counterpartyAccountNo, memo, idempotencyKey, now);
            ps.executeUpdate();
        }
    }

    /** 振込の出金側・入金側の明細を 1 回の JDBC バッチ（DB 往復 1 回）で登録する。 */
    public void insertTransfer(Connection c, String fromAccountNo, BigDecimal fromBalanceAfter,
                               String toAccountNo, BigDecimal toBalanceAfter, BigDecimal amount,
                               String memo, String idempotencyKey, LocalDateTime now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(INSERT)) {
            bind(ps, fromAccountNo, TxType.TRANSFER_OUT, amount, fromBalanceAfter, toAccountNo, memo, idempotencyKey, now);
            ps.addBatch();
            bind(ps, toAccountNo, TxType.TRANSFER_IN, amount, toBalanceAfter, fromAccountNo, memo, idempotencyKey, now);
            ps.addBatch();
            ps.executeBatch();
        }
    }

    /** 新しい順に最大 limit 件。 */
    public List<AccountTransaction> findRecent(Connection c, String accountNo, int limit) throws SQLException {
        String sql = "SELECT " + COLS + " FROM account_transaction WHERE account_no = ? "
                   + "ORDER BY tx_id DESC FETCH FIRST ? ROWS ONLY";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, accountNo);
            ps.setInt(2, limit);
            // 既定プリフェッチ（Oracle は 10 行）では 50 件取得に 5 往復以上かかるため、limit 件を 1 往復で取得する
            ps.setFetchSize(limit);
            return readAll(ps);
        }
    }

    /** 条件に合う明細を新しい順に offset から limit 件。 */
    public List<AccountTransaction> search(Connection c, Filter f, int offset, int limit) throws SQLException {
        List<Object> params = new ArrayList<>();
        String sql = "SELECT " + COLS + " FROM account_transaction" + where(f, params)
                   + " ORDER BY created_at DESC, tx_id DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        params.add(offset);
        params.add(limit);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setFetchSize(limit);
            bindAll(ps, params);
            return readAll(ps);
        }
    }

    /** 条件に合う明細の件数と入金・出金の合計。 */
    public Totals totals(Connection c, Filter f) throws SQLException {
        List<Object> params = new ArrayList<>();
        String sql = "SELECT COUNT(*), "
                   + "COALESCE(SUM(CASE WHEN tx_type IN " + CREDIT_TYPES + " THEN amount ELSE 0 END), 0), "
                   + "COALESCE(SUM(CASE WHEN tx_type IN " + CREDIT_TYPES + " THEN 0 ELSE amount END), 0) "
                   + "FROM account_transaction" + where(f, params);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bindAll(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Totals(rs.getLong(1), rs.getBigDecimal(2), rs.getBigDecimal(3));
            }
        }
    }

    /** 指定日時より前の最後の取引後残高（取引がなければ 0）。 */
    public BigDecimal balanceBefore(Connection c, String accountNo, LocalDateTime before) throws SQLException {
        String sql = "SELECT balance_after FROM account_transaction WHERE account_no = ? AND created_at < ? "
                   + "ORDER BY created_at DESC, tx_id DESC FETCH FIRST 1 ROWS ONLY";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, accountNo);
            ps.setTimestamp(2, Timestamp.valueOf(before));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
            }
        }
    }

    /** 期間内の残高変化（日時の昇順）。利息計算用。 */
    public List<BalanceChange> balanceChanges(Connection c, String accountNo, LocalDateTime from,
                                              LocalDateTime toExclusive) throws SQLException {
        String sql = "SELECT created_at, balance_after FROM account_transaction "
                   + "WHERE account_no = ? AND created_at >= ? AND created_at < ? ORDER BY created_at, tx_id";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setFetchSize(500);
            ps.setString(1, accountNo);
            ps.setTimestamp(2, Timestamp.valueOf(from));
            ps.setTimestamp(3, Timestamp.valueOf(toExclusive));
            try (ResultSet rs = ps.executeQuery()) {
                List<BalanceChange> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(new BalanceChange(rs.getTimestamp(1).toLocalDateTime(), rs.getBigDecimal(2)));
                }
                return list;
            }
        }
    }

    private static String where(Filter f, List<Object> params) {
        StringBuilder sb = new StringBuilder(" WHERE account_no = ?");
        params.add(f.accountNo);
        if (f.from != null) {
            sb.append(" AND created_at >= ?");
            params.add(Timestamp.valueOf(f.from));
        }
        if (f.toExclusive != null) {
            sb.append(" AND created_at < ?");
            params.add(Timestamp.valueOf(f.toExclusive));
        }
        if (!f.types.isEmpty()) {
            sb.append(" AND tx_type IN (");
            for (int i = 0; i < f.types.size(); i++) {
                sb.append(i == 0 ? "?" : ", ?");
                params.add(f.types.get(i).name());
            }
            sb.append(')');
        }
        return sb.toString();
    }

    private static void bindAll(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
    }

    private static List<AccountTransaction> readAll(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<AccountTransaction> list = new ArrayList<>();
            while (rs.next()) {
                list.add(new AccountTransaction(
                        rs.getLong("tx_id"),
                        rs.getString("account_no"),
                        rs.getString("tx_type"),
                        rs.getBigDecimal("amount"),
                        rs.getBigDecimal("balance_after"),
                        rs.getString("counterparty_account_no"),
                        rs.getString("memo"),
                        Timestamps.format(rs.getTimestamp("created_at"))));
            }
            return list;
        }
    }

    private static void bind(PreparedStatement ps, String accountNo, TxType type, BigDecimal amount,
                             BigDecimal balanceAfter, String counterpartyAccountNo, String memo,
                             String idempotencyKey, LocalDateTime now) throws SQLException {
        ps.setString(1, accountNo);
        ps.setString(2, type.name());
        ps.setBigDecimal(3, amount);
        ps.setBigDecimal(4, balanceAfter);
        ps.setString(5, counterpartyAccountNo);
        ps.setString(6, memo);
        ps.setString(7, idempotencyKey);
        ps.setTimestamp(8, Timestamp.valueOf(now));
    }
}
