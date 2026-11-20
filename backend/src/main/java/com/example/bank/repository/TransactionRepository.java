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
            + "(account_no, tx_type, amount, balance_after, counterparty_account_no, memo, idempotency_key, created_at, "
            + "prev_hash, row_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String COLS =
            "tx_id, account_no, tx_type, amount, balance_after, counterparty_account_no, memo, created_at";
    // ※ 集計 SQL（totals）で入金として数える種別。現状は TxType.isCredit にある FX_IN / LOAN_DISB / REMIT_IN を含まず、
    //   これらは出金合計に入る。種別を追加・変更するときは両方を確認すること
    /** 残高が増える取引種別（集計用。TxType.isCredit と一致させる） */
    private static final String CREDIT_TYPES = "('DEPOSIT', 'TRANSFER_IN', 'INTEREST')";

    /** 取引履歴の検索条件。from は含む、toExclusive は含まない。null は条件なし。 */
    public static final class Filter {
        final String accountNo;
        final LocalDateTime from;
        final LocalDateTime toExclusive;
        final List<TxType> types;

        /**
         * @param accountNo   口座番号
         * @param from        期間の開始（含む。null は条件なし）
         * @param toExclusive 期間の終了（含まない。null は条件なし）
         * @param types       取引種別（null・空は全種別）
         */
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

    /**
     * 明細を 1 件登録する（口座ごとのハッシュチェーンを延長する）。
     *
     * @param c                     DB 接続（口座行をロック済みのトランザクション）
     * @param accountNo             口座番号
     * @param type                  取引種別
     * @param amount                金額（正の値）
     * @param balanceAfter          取引後残高
     * @param counterpartyAccountNo 相手口座（なければ null）
     * @param memo                  摘要
     * @param idempotencyKey        操作の冪等キー（監査ログとの対応付けに使う）
     * @param now                   記帳日時
     * @throws SQLException DB アクセスに失敗した場合
     */
    public void insert(Connection c, String accountNo, TxType type, BigDecimal amount, BigDecimal balanceAfter,
                       String counterpartyAccountNo, String memo, String idempotencyKey, LocalDateTime now)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(INSERT)) {
            bind(c, ps, accountNo, type, amount, balanceAfter, counterpartyAccountNo, memo, idempotencyKey, now);
            ps.executeUpdate();
        }
    }

    /** 振込の出金側・入金側の明細を 1 回の JDBC バッチ（DB 往復 1 回）で登録する。 */
    public void insertTransfer(Connection c, String fromAccountNo, BigDecimal fromBalanceAfter,
                               String toAccountNo, BigDecimal toBalanceAfter, BigDecimal amount,
                               String memo, String idempotencyKey, LocalDateTime now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(INSERT)) {
            bind(c, ps, fromAccountNo, TxType.TRANSFER_OUT, amount, fromBalanceAfter, toAccountNo, memo, idempotencyKey, now);
            ps.addBatch();
            // 出金側と入金側を同じ冪等キーで記録し、2 つの明細が 1 つの振込であることを追跡できるようにする
            bind(c, ps, toAccountNo, TxType.TRANSFER_IN, amount, toBalanceAfter, fromAccountNo, memo, idempotencyKey, now);
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

    /** 指定日時以降の出金・振込（出）の合計（日次上限の判定用）。 */
    public BigDecimal outflowSince(Connection c, String accountNo, LocalDateTime since) throws SQLException {
        String sql = "SELECT COALESCE(SUM(amount), 0) FROM account_transaction "
                   + "WHERE account_no = ? AND created_at >= ? AND tx_type IN ('WITHDRAWAL', 'TRANSFER_OUT')";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, accountNo);
            ps.setTimestamp(2, Timestamp.valueOf(since));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
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

    // 指定された条件だけを AND でつなぎ、値はすべてバインド変数として params に追加する
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

    // where() で積んだ順にバインド変数を設定する
    private static void bindAll(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
    }

    // SELECT 結果を AccountTransaction のリストに変換する
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

    /**
     * 明細の値を設定し、口座ごとのハッシュチェーン（直前の明細のハッシュ + この明細の内容）を計算する。
     * 呼び出し側は口座行をロックしていること（同じ口座の明細が同時に追加されないため、チェーンが一本になる）。
     */
    private static void bind(Connection c, PreparedStatement ps, String accountNo, TxType type, BigDecimal amount,
                             BigDecimal balanceAfter, String counterpartyAccountNo, String memo,
                             String idempotencyKey, LocalDateTime time) throws SQLException {
        // Second precision: the hashed timestamp must read back identically from Oracle and H2
        LocalDateTime now = time.withNano(0);
        // 直前の明細のハッシュを鎖の前方参照にする。過去の明細を書き換えると、それ以降のハッシュがすべて合わなくなる
        String prev = com.example.bank.service.LedgerService.lastHash(c, accountNo);
        // prev が null（ハッシュ導入前の口座）の場合はチェーンを始めない
        String hash = prev == null ? null : com.example.bank.service.LedgerService.hash(
                com.example.bank.service.LedgerService.canonical(accountNo, type.name(), amount, balanceAfter,
                        counterpartyAccountNo, memo, idempotencyKey, now.toString(), prev));
        ps.setString(9, prev);
        ps.setString(10, hash);
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
