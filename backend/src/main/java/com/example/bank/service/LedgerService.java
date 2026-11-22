package com.example.bank.service;

import com.example.bank.domain.TxType;
import com.example.bank.support.DataSources;
import com.example.bank.support.PasswordHasher;
import com.example.bank.support.Transactions;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.example.bank.repository.ProductRepository.execute;
import static com.example.bank.repository.ProductRepository.query;

/**
 * 取引元帳の整合性（ACID の「一貫性」の事後検証と、改ざん検知）。
 *
 * <ul>
 *   <li>残高の連続性: 明細ごとに「直前の取引後残高 ± 金額 = 取引後残高」（入金系は加算・出金系は減算）</li>
 *   <li>残高照合: 口座の残高 = 最新明細の取引後残高</li>
 *   <li>振込・振替の対応: 同じ冪等キーの出金側（TRANSFER_OUT、口座への解約払戻しの CLOSE_PAYOUT）と TRANSFER_IN の金額が一致</li>
 *   <li>ハッシュチェーン: 明細は口座ごとに直前の明細のハッシュを含む SHA-256 でつながる（明細の書換え・削除・差込みを検出）</li>
 * </ul>
 * ハッシュは明細の登録時（口座行をロック中）に計算する。この機能より前の明細は最初の検証時に一度だけハッシュを付ける。
 */
public class LedgerService {
    /** ハッシュチェーンの起点（口座の最初の明細の prevHash） */
    public static final String GENESIS = "0000000000000000000000000000000000000000000000000000000000000000";

    private static final LedgerService DEFAULT = new LedgerService(DataSources::bank, Clock.systemDefaultZone());

    private final Supplier<DataSource> dataSource;
    private final Clock clock;

    /**
     * @param dataSource 接続の取得元
     * @param clock      検証日時の記録に使う時計
     */
    public LedgerService(Supplier<DataSource> dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    /** @return アプリケーション既定のインスタンス */
    public static LedgerService getDefault() { return DEFAULT; }

    /** 明細 1 行のハッシュ対象（区切りは改行、null は空）。 */
    public static String canonical(String accountNo, String txType, BigDecimal amount, BigDecimal balanceAfter,
                                   String counterparty, String memo, String idempotencyKey, String createdAt,
                                   String prevHash) {
        StringBuilder sb = new StringBuilder();
        for (Object v : new Object[]{accountNo, txType, plain(amount), plain(balanceAfter), counterparty, memo,
                idempotencyKey, createdAt, prevHash}) {
            // 区切り文字（改行）をエスケープし、項目の境界をずらしても同じ文字列にならないようにする
            sb.append(v == null ? "" : v.toString().replace("\\", "\\\\").replace("\n", "\\n")).append('\n');
        }
        return sb.toString();
    }

    /**
     * @param canonical {@link #canonical} で作った正規化文字列
     * @return SHA-256（16 進小文字）
     */
    public static String hash(String canonical) {
        return PasswordHasher.sha256Hex(canonical);
    }

    // DB によって 100 と 100.00 のように桁数が違って返っても同じハッシュになるよう、数値表記をそろえる
    private static String plain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    /** 口座の最新明細のハッシュ（なければ起点値）。口座行のロック中に呼ぶこと。 */
    public static String lastHash(Connection c, String accountNo) throws SQLException {
        List<Map<String, Object>> rows = query(c, "SELECT row_hash FROM account_transaction WHERE account_no = ? "
                // 口座行をロック中に読むので、この「最新」が登録までに別の明細に追い越されることはない（チェーンが分岐しない）
                + "ORDER BY tx_id DESC FETCH FIRST 1 ROWS ONLY", accountNo);
        if (rows.isEmpty()) return GENESIS;
        Object h = rows.get(0).get("rowHash");
        return h == null ? null : (String) h;   // null: legacy rows not hashed yet -> backfilled on verification
    }

    /**
     * 元帳を検証する（accountNo が null なら全口座）。検証の前に、ハッシュ未設定の旧明細へハッシュを付ける。
     *
     * @return 検証結果（問題のある口座と理由の一覧を含む）
     */
    public Map<String, Object> verify(String accountNo) {
        // 旧明細にハッシュがないと検証できないため、検証の前に付ける（付与済みなら何もしない）
        backfill();
        return Transactions.readOnly(dataSource.get(), c -> {
            List<Map<String, Object>> accounts = accountNo == null
                    ? query(c, "SELECT account_no, balance FROM account ORDER BY account_no")
                    : query(c, "SELECT account_no, balance FROM account WHERE account_no = ?", accountNo);
            List<Map<String, Object>> problems = new ArrayList<>();
            long rows = 0;
            for (Map<String, Object> a : accounts) {
                String no = (String) a.get("accountNo");
                List<Map<String, Object>> txs = query(c, "SELECT tx_id, tx_type, amount, balance_after, counterparty_account_no, "
                        + "memo, idempotency_key, created_at, prev_hash, row_hash FROM account_transaction WHERE account_no = ? "
                        + "ORDER BY tx_id", no);
                rows += txs.size();
                String prevHash = GENESIS;
                BigDecimal prevBalance = null;
                for (Map<String, Object> t : txs) {
                    long txId = LoanService.num(t.get("txId")).longValue();
                    BigDecimal amount = LoanService.num(t.get("amount"));
                    BigDecimal after = LoanService.num(t.get("balanceAfter"));
                    String type = (String) t.get("txType");
                    if (prevBalance != null) {
                        // 入金系は直前残高に加算、出金系は減算した値が、この明細の取引後残高と一致するはず
                        BigDecimal expected = TxType.valueOf(type).isCredit() ? prevBalance.add(amount) : prevBalance.subtract(amount);
                        if (expected.compareTo(after) != 0) {
                            problems.add(problem(no, txId, "残高の連続性", "直前残高 " + prevBalance + " → 期待 " + expected + "、記録 " + after));
                        }
                    }
                    String h = hash(canonical(no, type, amount, after, (String) t.get("counterpartyAccountNo"),
                            (String) t.get("memo"), (String) t.get("idempotencyKey"), (String) t.get("createdAt"), prevHash));
                    if (!prevHash.equals(t.get("prevHash")) || !h.equals(t.get("rowHash"))) {
                        problems.add(problem(no, txId, "ハッシュチェーン", "明細が改ざん・削除・差込みされた可能性"));
                    }
                    // 記録されたハッシュで次の行をつなぐ（1 行の改ざんで以降すべてが不一致になるのを避け、問題の行だけを報告する）
                    prevHash = t.get("rowHash") == null ? h : (String) t.get("rowHash");
                    prevBalance = after;
                }
                BigDecimal balance = LoanService.num(a.get("balance"));
                BigDecimal last = prevBalance == null ? BigDecimal.ZERO : prevBalance;
                if (balance.compareTo(last) != 0) {
                    problems.add(problem(no, null, "残高照合", "口座残高 " + balance + " ≠ 最新明細の取引後残高 " + last));
                }
            }
            // Transfers: for each key the outgoing side (TRANSFER_OUT, or CLOSE_PAYOUT when a closed account is paid
            // into another account) equals the incoming side (TRANSFER_IN). A cash closure payout has no pair.
            String out = "SUM(CASE WHEN tx_type IN ('TRANSFER_OUT', 'CLOSE_PAYOUT') THEN amount ELSE 0 END)";
            String in = "SUM(CASE WHEN tx_type = 'TRANSFER_IN' THEN amount ELSE 0 END)";
            for (Map<String, Object> m : query(c, "SELECT idempotency_key, " + out + " AS out_amount, " + in + " AS in_amount "
                    + "FROM account_transaction WHERE tx_type IN ('TRANSFER_OUT', 'TRANSFER_IN', 'CLOSE_PAYOUT') "
                    + "AND idempotency_key IS NOT NULL GROUP BY idempotency_key "
                    + "HAVING SUM(CASE WHEN tx_type IN ('TRANSFER_OUT', 'TRANSFER_IN') THEN 1 ELSE 0 END) > 0 "
                    + "AND " + out + " <> " + in)) {
                problems.add(problem(null, null, "振込の対応", "キー " + m.get("idempotencyKey") + ": 出金 " + m.get("outAmount")
                        + " ≠ 入金 " + m.get("inAmount")));
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("checkedAt", LocalDateTime.now(clock).withNano(0).toString());
            r.put("accounts", accounts.size());
            r.put("transactions", rows);
            r.put("valid", problems.isEmpty());
            r.put("problems", problems);
            return r;
        });
    }

    /** ハッシュ未設定の明細（この機能より前の明細）に、口座ごとにハッシュを付ける。 */
    public int backfill() {
        List<Map<String, Object>> accounts = Transactions.readOnly(dataSource.get(), c -> query(c,
                "SELECT DISTINCT account_no FROM account_transaction WHERE row_hash IS NULL"));
        int filled = 0;
        for (Map<String, Object> a : accounts) {
            String no = (String) a.get("accountNo");
            // 口座ごとに別トランザクションにし、ロックを長く持ち続けない（全口座を 1 トランザクションにすると記帳が長時間止まる）
            filled += Transactions.inTransaction(dataSource.get(), c -> {
                // 記帳処理と同じ口座行ロックを取り、ハッシュを付けている間に新しい明細が割り込んでチェーンが分岐するのを防ぐ
                query(c, "SELECT account_no FROM account WHERE account_no = ? FOR UPDATE", no);   // same lock as postings
                int n = 0;
                String prev = GENESIS;
                for (Map<String, Object> t : query(c, "SELECT tx_id, tx_type, amount, balance_after, counterparty_account_no, "
                        + "memo, idempotency_key, created_at, prev_hash, row_hash FROM account_transaction WHERE account_no = ? "
                        + "ORDER BY tx_id", no)) {
                    if (t.get("rowHash") != null) {
                        prev = (String) t.get("rowHash");
                        continue;
                    }
                    String h = hash(canonical(no, (String) t.get("txType"), LoanService.num(t.get("amount")),
                            LoanService.num(t.get("balanceAfter")), (String) t.get("counterpartyAccountNo"),
                            (String) t.get("memo"), (String) t.get("idempotencyKey"), (String) t.get("createdAt"), prev));
                    execute(c, "UPDATE account_transaction SET prev_hash = ?, row_hash = ? WHERE tx_id = ?", prev, h, t.get("txId"));
                    prev = h;
                    n++;
                }
                return n;
            });
        }
        return filled;
    }

    /**
     * 取引の追跡: 口座の明細と、それを起こした操作（監査ログ: 誰が・いつ・どこから）を冪等キーで結び付ける。
     */
    public List<Map<String, Object>> trace(String accountNo, int limit) {
        return Transactions.readOnly(dataSource.get(), c -> {
            List<Map<String, Object>> txs = query(c, "SELECT tx_id, tx_type, amount, balance_after, counterparty_account_no, "
                    + "memo, idempotency_key, created_at, row_hash FROM account_transaction WHERE account_no = ? "
                    // 件数は 1〜200 に丸めた整数なので文字列に埋め込んでも SQL インジェクションにはならない
                    + "ORDER BY tx_id DESC FETCH FIRST " + Math.max(1, Math.min(limit, 200)) + " ROWS ONLY", accountNo);
            // 明細ごとに監査ログを問い合わせると最大 200 回の DB 往復になる（N+1）ため、冪等キーをまとめて 1 回で取得する。
            // キーは最大 200 件なので、Oracle の IN リストの上限（1000）に収まる
            Map<Object, List<Map<String, Object>>> opsByKey = new LinkedHashMap<>();
            for (Map<String, Object> t : txs) {
                Object key = t.get("idempotencyKey");
                if (key != null) opsByKey.put(key, new ArrayList<Map<String, Object>>());
            }
            if (!opsByKey.isEmpty()) {
                StringBuilder in = new StringBuilder();
                for (int i = 0; i < opsByKey.size(); i++) in.append(i == 0 ? "?" : ", ?");
                for (Map<String, Object> op : query(c, "SELECT request_key, audit_id, occurred_at, user_id, user_role, action, "
                        + "client_ip, outcome FROM audit_log WHERE request_key IN (" + in + ") ORDER BY audit_id",
                        opsByKey.keySet().toArray())) {
                    // 応答の形は従来と同じにする（結び付けに使った request_key は返さない）
                    List<Map<String, Object>> list = opsByKey.get(op.remove("requestKey"));
                    if (list != null) list.add(op);
                }
            }
            for (Map<String, Object> t : txs) {
                Object key = t.get("idempotencyKey");
                List<Map<String, Object>> ops = key == null ? new ArrayList<Map<String, Object>>() : opsByKey.get(key);
                t.put("operations", ops);
                if (key == null) t.put("origin", "システム処理（利息・満期・夜間バッチ等）");
            }
            return txs;
        });
    }

    // 検証で見つかった問題 1 件を表す
    private static Map<String, Object> problem(String accountNo, Long txId, String check, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accountNo", accountNo);
        m.put("txId", txId);
        m.put("check", check);
        m.put("detail", detail);
        return m;
    }
}
