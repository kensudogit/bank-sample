package com.example.bank.repository;

import com.example.bank.domain.Account;
import com.example.bank.support.Timestamps;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** ACCOUNT / CUSTOMER への JDBC アクセス。接続とトランザクションは呼び出し側が管理する。 */
public class AccountRepository {

    // 取得する列を 1 か所で定義し、照会系とロック系（lockById）で同じ列順を使う
    private static final String ACCOUNT_COLS =
            "a.account_no, a.customer_id, a.account_type, a.balance, a.status, a.updated_at, a.opened_at, "
          + "a.closed_at, a.last_activity_at, a.dormancy_notified_at, a.dormant_at, a.currency, a.overdraft_limit";
    private static final String SELECT_WITH_CUSTOMER =
            "SELECT " + ACCOUNT_COLS + ", c.name AS customer_name "
          + "FROM account a JOIN customer c ON c.customer_id = a.customer_id ";

    /** 顧客による取引: 最終異動日を更新し、休眠前通知はリセットする */
    private static final String UPDATE_BALANCE_ACTIVITY =
            "UPDATE account SET balance = ?, updated_at = ?, last_activity_at = ?, dormancy_notified_at = NULL "
          + "WHERE account_no = ?";
    /** 利息・税など銀行側の処理: 最終異動日は変えない（休眠預金等活用法の「異動」に当たらない） */
    private static final String UPDATE_BALANCE_SYSTEM =
            "UPDATE account SET balance = ?, updated_at = ? WHERE account_no = ?";
    // 一覧取得で 1 回の DB 往復あたりに受け取る行数
    private static final int LIST_FETCH_SIZE = 200;

    /**
     * 全口座を口座番号順に返す。
     *
     * @param c DB 接続
     * @return 口座一覧（名義人名付き）
     * @throws SQLException DB アクセスに失敗した場合
     */
    public List<Account> findAll(Connection c) throws SQLException {
        return list(c, SELECT_WITH_CUSTOMER + "ORDER BY a.account_no");
    }

    /**
     * 顧客の保有口座を返す（解約済みを含む）。
     *
     * @param c          DB 接続
     * @param customerId 顧客番号
     * @return 口座一覧
     * @throws SQLException DB アクセスに失敗した場合
     */
    public List<Account> findByCustomer(Connection c, String customerId) throws SQLException {
        return list(c, SELECT_WITH_CUSTOMER + "WHERE a.customer_id = ? ORDER BY a.account_no", customerId);
    }

    /**
     * 状態で口座を絞り込む。
     *
     * @param c      DB 接続
     * @param status 口座状態
     * @return 口座一覧
     * @throws SQLException DB アクセスに失敗した場合
     */
    public List<Account> findByStatus(Connection c, String status) throws SQLException {
        return list(c, SELECT_WITH_CUSTOMER + "WHERE a.status = ? ORDER BY a.account_no", status);
    }

    /** 利息決算の対象（解約済み以外）の口座番号。 */
    public List<String> findOpenAccountNos(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT account_no FROM account WHERE status <> 'CLOSED' ORDER BY account_no")) {
            ps.setFetchSize(LIST_FETCH_SIZE);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> list = new ArrayList<>();
                while (rs.next()) list.add(rs.getString(1));
                return list;
            }
        }
    }

    /** 件数と残高合計。 */
    public static final class Summary {
        public final long count;
        public final BigDecimal totalBalance;

        Summary(long count, BigDecimal totalBalance) {
            this.count = count;
            this.totalBalance = totalBalance;
        }
    }

    /**
     * 検索条件に合う口座の件数と残高合計を返す（一覧のページングとは別に、全件の集計を 1 回で求める）。
     *
     * @param c             DB 接続
     * @param customerScope 顧客ログインなら本人の顧客番号、行員なら null
     * @param q             検索語（null は条件なし）
     * @return 件数と残高合計
     * @throws SQLException DB アクセスに失敗した場合
     */
    public Summary summarize(Connection c, String customerScope, String q) throws SQLException {
        List<Object> params = new ArrayList<>();
        String sql = "SELECT COUNT(*), COALESCE(SUM(a.balance), 0) FROM account a "
                   + "JOIN customer c ON c.customer_id = a.customer_id" + searchWhere(customerScope, q, params);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params.toArray());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Summary(rs.getLong(1), rs.getBigDecimal(2));
            }
        }
    }

    /**
     * 検索条件に合う口座を 1 ページ分返す。
     *
     * @param c             DB 接続
     * @param customerScope 顧客ログインなら本人の顧客番号、行員なら null
     * @param q             検索語（null は条件なし）
     * @param offset        読み飛ばす件数
     * @param limit         取得件数
     * @return 口座一覧
     * @throws SQLException DB アクセスに失敗した場合
     */
    public List<Account> search(Connection c, String customerScope, String q, int offset, int limit)
            throws SQLException {
        List<Object> params = new ArrayList<>();
        String sql = SELECT_WITH_CUSTOMER.trim() + searchWhere(customerScope, q, params)
                   + " ORDER BY a.account_no OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        params.add(offset);
        params.add(limit);
        return list(c, sql, params.toArray());
    }

    // 条件は必ずバインド変数で渡し、利用者の入力を SQL 文字列に連結しない（SQL インジェクション対策）
    private static String searchWhere(String customerScope, String q, List<Object> params) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1");
        if (customerScope != null) {
            sb.append(" AND a.customer_id = ?");
            params.add(customerScope);
        }
        if (q != null && !q.isEmpty()) {
            // LIKE wildcards in the user input are escaped (escape character '!') so they match literally
            String escaped = q.replace("!", "!!").replace("%", "!%").replace("_", "!_");
            sb.append(" AND (a.account_no LIKE ? ESCAPE '!' OR a.customer_id = ? "
                    + "OR c.name LIKE ? ESCAPE '!' OR c.name_kana LIKE ? ESCAPE '!')");
            params.add(escaped + "%");
            params.add(q.toUpperCase());
            params.add("%" + escaped + "%");
            params.add("%" + escaped + "%");
        }
        return sb.toString();
    }

    /**
     * 口座を取得する（ロックしない照会用）。
     *
     * @param c         DB 接続
     * @param accountNo 口座番号
     * @return 口座（存在しなければ空）
     * @throws SQLException DB アクセスに失敗した場合
     */
    public Optional<Account> findById(Connection c, String accountNo) throws SQLException {
        List<Account> list = list(c, SELECT_WITH_CUSTOMER + "WHERE a.account_no = ?", accountNo);
        return list.isEmpty() ? Optional.<Account>empty() : Optional.of(list.get(0));
    }

    /**
     * 口座の名義人の顧客番号だけを取得する（アクセス権の確認用）。
     * ほぼすべての口座系 API の前に呼ばれるため、顧客表を結合せず主キーで 1 列だけ読む。
     *
     * @param c         DB 接続
     * @param accountNo 口座番号
     * @return 顧客番号（口座が存在しなければ空）
     * @throws SQLException DB アクセスに失敗した場合
     */
    public Optional<String> findOwner(Connection c, String accountNo) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT customer_id FROM account WHERE account_no = ?")) {
            ps.setString(1, accountNo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.<String>empty();
            }
        }
    }

    /**
     * 行ロック付きで取得（SELECT ... FOR UPDATE）。顧客表は結合しない（顧客行までロックしないため）。
     * デッドロック回避のため、複数口座をロックする場合は口座番号順にロックすること。
     */
    public Optional<Account> lockById(Connection c, String accountNo) throws SQLException {
        // FOR UPDATE の行ロックはトランザクションの終了（コミット・ロールバック）まで保持され、同じ口座への同時更新を直列化する。
        // これにより「残高を読む → 計算 → 書き戻す」の間に他の取引が割り込んで残高が狂う（更新の消失）ことを防ぐ
        String sql = "SELECT " + ACCOUNT_COLS + " FROM account a WHERE a.account_no = ? FOR UPDATE";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, accountNo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs, null)) : Optional.<Account>empty();
            }
        }
    }

    /** 同じ顧客・同じ種別の、解約済みでない口座の数。 */
    public int countOpenByCustomerAndType(Connection c, String customerId, String accountType) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM account WHERE customer_id = ? AND account_type = ? AND status <> 'CLOSED'")) {
            ps.setString(1, customerId);
            ps.setString(2, accountType);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * 口座番号をシーケンスで採番する（同時に開設しても重複しない）。
     *
     * @param c DB 接続
     * @return 新しい口座番号
     * @throws SQLException DB アクセスに失敗した場合
     */
    public String nextAccountNo(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT account_no_seq.NEXTVAL FROM dual");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return String.valueOf(rs.getLong(1));
        }
    }

    /**
     * 円口座を登録する。
     *
     * @param c           DB 接続
     * @param accountNo   口座番号
     * @param customerId  顧客番号
     * @param accountType 口座種別
     * @param balance     初期残高
     * @param now         開設日時
     * @throws SQLException DB アクセスに失敗した場合
     */
    public void insert(Connection c, String accountNo, String customerId, String accountType, BigDecimal balance,
                       LocalDateTime now) throws SQLException {
        insert(c, accountNo, customerId, accountType, "JPY", balance, now);
    }

    /**
     * 通貨を指定して口座を登録する。
     *
     * @param currency 通貨コード（JPY / USD / EUR）
     * 他の引数は円口座版の insert と同じ。
     */
    public void insert(Connection c, String accountNo, String customerId, String accountType, String currency,
                       BigDecimal balance, LocalDateTime now) throws SQLException {
        String sql = "INSERT INTO account (account_no, customer_id, account_type, balance, status, opened_at, "
                   + "updated_at, last_activity_at, currency) VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            Timestamp ts = Timestamp.valueOf(now);
            ps.setString(1, accountNo);
            ps.setString(2, customerId);
            ps.setString(3, accountType);
            ps.setBigDecimal(4, balance);
            ps.setTimestamp(5, ts);
            ps.setTimestamp(6, ts);
            ps.setTimestamp(7, ts);
            ps.setString(8, currency);
            ps.executeUpdate();
        }
    }

    /** 当座貸越の極度額を設定する（当座貸越契約の実行・解約時）。 */
    public void setOverdraftLimit(Connection c, String accountNo, BigDecimal limit, LocalDateTime now)
            throws SQLException {
        execute(c, "UPDATE account SET overdraft_limit = ?, updated_at = ? WHERE account_no = ?", limit, now, accountNo);
    }

    /** 同じ顧客・同じ種別・同じ通貨の、解約済みでない口座の数。 */
    public int countOpenByCustomerTypeCurrency(Connection c, String customerId, String accountType, String currency)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM account WHERE customer_id = ? "
                + "AND account_type = ? AND currency = ? AND status <> 'CLOSED'")) {
            ps.setString(1, customerId);
            ps.setString(2, accountType);
            ps.setString(3, currency);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * @param customerActivity true: 顧客による取引（最終異動日を更新）、false: 利息・税などの銀行側処理
     */
    public void updateBalance(Connection c, String accountNo, BigDecimal newBalance, LocalDateTime now,
                              boolean customerActivity) throws SQLException {
        // 呼び出し側は lockById で行ロックを取得済みであること（ロックなしで更新すると同時取引で残高が狂う）
        try (PreparedStatement ps = c.prepareStatement(customerActivity ? UPDATE_BALANCE_ACTIVITY : UPDATE_BALANCE_SYSTEM)) {
            bindBalance(ps, accountNo, newBalance, now, customerActivity);
            // 更新件数が 1 でなければ想定外の状態なので、例外にしてトランザクション全体をロールバックさせる
            if (ps.executeUpdate() != 1) throw new SQLException("account row not updated: " + accountNo);
        }
    }

    /**
     * 振込: 2 口座の残高を 1 回の JDBC バッチ（DB 往復 1 回）で更新する（顧客による取引として最終異動日も更新）。
     * 行ロックを保持する時間を短くするため。
     */
    public void updateBalances(Connection c, String accountNo1, BigDecimal newBalance1,
                               String accountNo2, BigDecimal newBalance2, LocalDateTime now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(UPDATE_BALANCE_ACTIVITY)) {
            bindBalance(ps, accountNo1, newBalance1, now, true);
            ps.addBatch();
            bindBalance(ps, accountNo2, newBalance2, now, true);
            ps.addBatch();
            int[] counts = ps.executeBatch();
            String[] accountNos = {accountNo1, accountNo2};
            for (int i = 0; i < counts.length; i++) {
                // SUCCESS_NO_INFO: 件数を返さないドライバー。行は FOR UPDATE で取得済みなので成功とみなす
                if (counts[i] != 1 && counts[i] != Statement.SUCCESS_NO_INFO) {
                    throw new SQLException("account row not updated: " + accountNos[i]);
                }
            }
        }
    }

    /** 解約: 残高 0・CLOSED にする（払戻しは呼び出し側で記帳済みであること）。 */
    public void close(Connection c, String accountNo, LocalDateTime now) throws SQLException {
        // status <> 'CLOSED' を条件に含め、二重に解約処理が走っても 2 回目は何も更新しない
        execute(c, "UPDATE account SET balance = 0, status = 'CLOSED', closed_at = ?, updated_at = ? "
                 + "WHERE account_no = ? AND status <> 'CLOSED'", now, now, accountNo);
    }

    /** 休眠前の通知を記録する。条件を UPDATE 内で再確認するため、直前に取引があった口座は対象外になる。 */
    public int markNotified(Connection c, String accountNo, LocalDateTime notifiedAt, LocalDateTime cutoff,
                            LocalDateTime now) throws SQLException {
        return execute(c, "UPDATE account SET dormancy_notified_at = ?, updated_at = ? "
                        + "WHERE account_no = ? AND status = 'ACTIVE' AND last_activity_at < ? "
                        + "AND dormancy_notified_at IS NULL",
                notifiedAt, now, accountNo, cutoff);
    }

    /** 休眠化。条件（ACTIVE かつ最終異動が基準より前）を UPDATE 内で再確認する。 */
    public int markDormant(Connection c, String accountNo, LocalDateTime dormantAt, LocalDateTime cutoff,
                           LocalDateTime now) throws SQLException {
        return execute(c, "UPDATE account SET status = 'DORMANT', dormant_at = ?, updated_at = ? "
                        + "WHERE account_no = ? AND status = 'ACTIVE' AND last_activity_at < ?",
                dormantAt, now, accountNo, cutoff);
    }

    /** 休眠解除: ACTIVE に戻し、最終異動日を現在にする。 */
    public void reactivate(Connection c, String accountNo, LocalDateTime now) throws SQLException {
        execute(c, "UPDATE account SET status = 'ACTIVE', dormant_at = NULL, dormancy_notified_at = NULL, "
                 + "last_activity_at = ?, updated_at = ? WHERE account_no = ? AND status = 'DORMANT'",
                now, now, accountNo);
    }

    /** 最終異動が cutoff より前の ACTIVE 口座（休眠管理の候補）。 */
    public List<Account> findActiveInactiveSince(Connection c, LocalDateTime cutoff) throws SQLException {
        return list(c, SELECT_WITH_CUSTOMER + "WHERE a.status = 'ACTIVE' AND a.last_activity_at < ? ORDER BY a.account_no",
                cutoff);
    }

    // 口座一覧を取得する共通処理（名義人名を含む SELECT_WITH_CUSTOMER 用）
    private List<Account> list(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            // Oracle の既定プリフェッチ（10 行）だと件数に比例して往復が増えるため、まとめて取得する
            ps.setFetchSize(LIST_FETCH_SIZE);
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<Account> list = new ArrayList<>();
                while (rs.next()) list.add(map(rs, rs.getString("customer_name")));
                return list;
            }
        }
    }

    private static int execute(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    // LocalDateTime は JDBC 4.2 未満のドライバーでも扱えるよう Timestamp に変換して渡す
    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p instanceof LocalDateTime) ps.setTimestamp(i + 1, Timestamp.valueOf((LocalDateTime) p));
            else ps.setObject(i + 1, p);
        }
    }

    // UPDATE_BALANCE_ACTIVITY と UPDATE_BALANCE_SYSTEM でプレースホルダーの数が違うため、顧客取引のときだけ最終異動日を設定する
    private static void bindBalance(PreparedStatement ps, String accountNo, BigDecimal newBalance, LocalDateTime now,
                                    boolean customerActivity) throws SQLException {
        int i = 1;
        ps.setBigDecimal(i++, newBalance);
        ps.setTimestamp(i++, Timestamp.valueOf(now));
        if (customerActivity) ps.setTimestamp(i++, Timestamp.valueOf(now));
        ps.setString(i, accountNo);
    }

    // ResultSet の現在行を Account に変換する（名義人名は結合した場合だけ渡される）
    private static Account map(ResultSet rs, String customerName) throws SQLException {
        return new Account(
                rs.getString("account_no"),
                rs.getString("customer_id"),
                customerName,
                rs.getString("account_type"),
                rs.getBigDecimal("balance"),
                rs.getString("status"),
                Timestamps.format(rs.getTimestamp("updated_at")),
                Timestamps.format(rs.getTimestamp("opened_at")),
                Timestamps.format(rs.getTimestamp("closed_at")),
                Timestamps.format(rs.getTimestamp("last_activity_at")),
                Timestamps.format(rs.getTimestamp("dormancy_notified_at")),
                Timestamps.format(rs.getTimestamp("dormant_at")),
                rs.getString("currency"),
                rs.getBigDecimal("overdraft_limit"));
    }
}
