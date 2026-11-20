package com.example.bank.service;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.support.DataSources;
import com.example.bank.support.Transactions;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static com.example.bank.repository.ProductRepository.execute;
import static com.example.bank.repository.ProductRepository.query;

/**
 * AML（マネー・ローンダリング対策）: 取引モニタリングと制裁リスト照合、アラートの案件管理。
 *
 * <ul>
 *   <li>LARGE_CASH: 200 万円以上の現金入出金（犯罪収益移転防止法の取引時確認の目安）</li>
 *   <li>STRUCTURING: 7 日以内に 50〜200 万円未満の現金入金が 3 回以上・合計 200 万円以上（分割による回避の疑い）</li>
 *   <li>RAPID_MOVEMENT: 100 万円以上の出金・送金で、直前 2 日の入金の 90% 以上を移動（通過口座の疑い）</li>
 *   <li>SANCTIONS / HIGH_RISK_COUNTRY: 海外送金の受取人の制裁リスト照合（名寄せの類似度 0.85 以上）、高リスク国</li>
 *   <li>FOREIGN_LARGE: 100 万円相当以上の海外送金（国外送金等調書の対象）</li>
 * </ul>
 * アラートは OPEN → INVESTIGATING → CLOSED（問題なし）/ REPORTED（疑わしい取引の届出）。
 */
public class AmlService {
    public static final BigDecimal LARGE_CASH = new BigDecimal("2000000");
    public static final BigDecimal STRUCT_MIN = new BigDecimal("500000");
    public static final BigDecimal RAPID_MIN = new BigDecimal("1000000");
    public static final BigDecimal FOREIGN_REPORT = new BigDecimal("1000000");
    public static final double NAME_MATCH = 0.85;
    private static final int SCAN_BATCH = 5000;

    private static final AmlService DEFAULT = new AmlService(DataSources::bank, Clock.systemDefaultZone());

    private final Supplier<DataSource> dataSource;
    private final Clock clock;

    public AmlService(Supplier<DataSource> dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    public static AmlService getDefault() { return DEFAULT; }

    // ===================== monitoring =====================

    /** 前回の続きから新しい取引を検査し、作成したアラート件数を返す。 */
    public Map<String, Object> scan() {
        return Transactions.inTransaction(dataSource.get(), c -> {
            long last = LoanService.num(query(c, "SELECT last_tx_id FROM aml_cursor WHERE cursor_id = 1 FOR UPDATE")
                    .get(0).get("lastTxId")).longValue();
            List<Map<String, Object>> txs = query(c, "SELECT t.tx_id, t.account_no, t.tx_type, t.amount, t.created_at, "
                    + "a.customer_id, a.currency FROM account_transaction t JOIN account a ON a.account_no = t.account_no "
                    + "WHERE t.tx_id > ? ORDER BY t.tx_id FETCH FIRST " + SCAN_BATCH + " ROWS ONLY", last);
            int created = 0;
            long max = last;
            for (Map<String, Object> t : txs) {
                long id = LoanService.num(t.get("txId")).longValue();
                max = Math.max(max, id);
                if (!"JPY".equals(t.get("currency"))) continue;
                created += checkTransaction(c, t, id);
            }
            execute(c, "UPDATE aml_cursor SET last_tx_id = ? WHERE cursor_id = 1", max);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scanned", txs.size());
            m.put("alerts", created);
            m.put("lastTxId", max);
            return m;
        });
    }

    private int checkTransaction(Connection c, Map<String, Object> t, long txId) throws SQLException {
        String type = (String) t.get("txType");
        String accountNo = (String) t.get("accountNo");
        String customerId = (String) t.get("customerId");
        BigDecimal amount = LoanService.num(t.get("amount"));
        LocalDateTime at = LocalDateTime.parse((String) t.get("createdAt"));
        int n = 0;
        boolean cash = "DEPOSIT".equals(type) || "WITHDRAWAL".equals(type);
        if (cash && amount.compareTo(LARGE_CASH) >= 0) {
            n += alert(c, "LARGE_CASH", customerId, accountNo, "TX:" + txId, 60,
                    String.format("現金%s %,d 円（200 万円以上）", "DEPOSIT".equals(type) ? "入金" : "出金", amount.longValue()));
        }
        if ("DEPOSIT".equals(type) && amount.compareTo(STRUCT_MIN) >= 0 && amount.compareTo(LARGE_CASH) < 0) {
            Map<String, Object> agg = query(c, "SELECT COUNT(*) AS n, COALESCE(SUM(amount), 0) AS total FROM account_transaction "
                    + "WHERE account_no = ? AND tx_type = 'DEPOSIT' AND amount >= ? AND amount < ? AND created_at > ? "
                    + "AND created_at <= ?", accountNo, STRUCT_MIN, LARGE_CASH, at.minusDays(7), at).get(0);
            int count = LoanService.num(agg.get("n")).intValue();
            BigDecimal total = LoanService.num(agg.get("total"));
            if (count >= 3 && total.compareTo(LARGE_CASH) >= 0) {
                n += alert(c, "STRUCTURING", customerId, accountNo, "STRUCT:" + accountNo + ":" + at.toLocalDate(), 75,
                        String.format("7 日以内に 50〜200 万円未満の現金入金 %d 回・合計 %,d 円", count, total.longValue()));
            }
        }
        boolean outflow = "WITHDRAWAL".equals(type) || "TRANSFER_OUT".equals(type) || "REMIT_OUT".equals(type);
        if (outflow && amount.compareTo(RAPID_MIN) >= 0) {
            BigDecimal inflow = LoanService.num(query(c, "SELECT COALESCE(SUM(amount), 0) AS v FROM account_transaction "
                    + "WHERE account_no = ? AND tx_type IN ('DEPOSIT', 'TRANSFER_IN', 'REMIT_IN') AND created_at >= ? "
                    + "AND tx_id < ?", accountNo, at.minusDays(2), txId).get(0).get("v"));
            if (inflow.compareTo(RAPID_MIN) >= 0 && amount.compareTo(inflow.multiply(new BigDecimal("0.9"))) >= 0) {
                n += alert(c, "RAPID_MOVEMENT", customerId, accountNo, "RAPID:" + txId, 65,
                        String.format("直前 2 日の入金 %,d 円のうち %,d 円を移動", inflow.longValue(), amount.longValue()));
            }
        }
        return n;
    }

    // ===================== screening =====================

    /** 制裁リスト・高リスク国の照合結果。 */
    public static final class Screening {
        public boolean hit;
        public int score;
        public String rule;
        public String detail = "該当なし";
    }

    /** 海外送金の受取人・国を照合する（呼び出し側のトランザクション内で実行）。 */
    public Screening screen(Connection c, String beneficiaryName, String country) throws SQLException {
        Screening s = new Screening();
        double best = 0;
        String bestName = null;
        for (Map<String, Object> e : query(c, "SELECT name, list_name FROM sanctions_list")) {
            double sim = nameSimilarity(beneficiaryName, (String) e.get("name"));
            if (sim > best) {
                best = sim;
                bestName = e.get("name") + "（" + e.get("listName") + "）";
            }
        }
        if (best >= NAME_MATCH) {
            s.hit = true;
            s.score = (int) Math.round(best * 100);
            s.rule = "SANCTIONS";
            s.detail = String.format("制裁リスト該当の可能性: %s 類似度 %.2f", bestName, best);
            return s;
        }
        List<Map<String, Object>> risk = query(c, "SELECT name FROM high_risk_country WHERE country = ?", country);
        if (!risk.isEmpty()) {
            s.hit = true;
            s.score = 70;
            s.rule = "HIGH_RISK_COUNTRY";
            s.detail = "高リスク国宛: " + country + "（" + risk.get(0).get("name") + "）";
        }
        return s;
    }

    /**
     * 名前の類似度（0〜1）: 英字以外を除いて単語に分け、語順の違いを吸収するため「語の集合の一致率」と
     * 「並べ替えた文字列の編集距離による類似度」の大きい方を使う。
     */
    public static double nameSimilarity(String a, String b) {
        List<String> ta = tokens(a);
        List<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return 0;
        Set<String> sa = new HashSet<>(ta);
        Set<String> sb = new HashSet<>(tb);
        Set<String> inter = new HashSet<>(sa);
        inter.retainAll(sb);
        double jaccard = (double) inter.size() / Math.max(sa.size(), sb.size());
        String ja = String.join(" ", sorted(ta));
        String jb = String.join(" ", sorted(tb));
        double lev = 1.0 - (double) levenshtein(ja, jb) / Math.max(ja.length(), jb.length());
        return Math.max(jaccard, lev);
    }

    private static List<String> tokens(String s) {
        if (s == null) return new ArrayList<>();
        String norm = s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", " ").trim();
        List<String> list = new ArrayList<>();
        for (String t : norm.split("\\s+")) {
            if (!t.isEmpty() && !Arrays.asList("LLC", "LTD", "INC", "CO", "CORP", "MR", "MRS", "MS").contains(t)) list.add(t);
        }
        return list;
    }

    private static List<String> sorted(List<String> l) {
        List<String> copy = new ArrayList<>(l);
        java.util.Collections.sort(copy);
        return copy;
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    // ===================== alerts =====================

    /** アラートを作る（同じルール・参照は 1 件だけ）。作成した場合 1。 */
    int alert(Connection c, String rule, String customerId, String accountNo, String reference, int score, String detail)
            throws SQLException {
        if (!query(c, "SELECT 1 FROM aml_alert WHERE rule_code = ? AND reference = ?", rule, reference).isEmpty()) return 0;
        LocalDateTime now = LocalDateTime.now(clock);
        execute(c, "INSERT INTO aml_alert (rule_code, customer_id, account_no, reference, score, detail, status, created_at, "
                + "updated_at) VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?, ?)", rule, customerId, accountNo, reference, score,
                detail.length() > 400 ? detail.substring(0, 400) : detail, now, now);
        return 1;
    }

    public List<Map<String, Object>> listAlerts(String status) {
        return Transactions.readOnly(dataSource.get(), c -> status == null
                ? query(c, "SELECT a.*, c.name AS customer_name FROM aml_alert a LEFT JOIN customer c ON c.customer_id = a.customer_id "
                        + "ORDER BY a.alert_id DESC FETCH FIRST 200 ROWS ONLY")
                : query(c, "SELECT a.*, c.name AS customer_name FROM aml_alert a LEFT JOIN customer c ON c.customer_id = a.customer_id "
                        + "WHERE a.status = ? ORDER BY a.alert_id DESC FETCH FIRST 200 ROWS ONLY", status));
    }

    /**
     * アラートの対応状況の更新。CLOSED（問題なし）・REPORTED（疑わしい取引の届出）は理由が必須。
     */
    public Map<String, Object> updateAlert(long alertId, String status, String note, String by) {
        if (!Arrays.asList("INVESTIGATING", "CLOSED", "REPORTED").contains(status)) throw new BankException(ErrorCode.VALIDATION_ERROR);
        if (!"INVESTIGATING".equals(status) && (note == null || note.trim().isEmpty())) {
            throw new BankException(ErrorCode.VALIDATION_ERROR);
        }
        return Transactions.inTransaction(dataSource.get(), c -> {
            List<Map<String, Object>> rows = query(c, "SELECT * FROM aml_alert WHERE alert_id = ? FOR UPDATE", alertId);
            if (rows.isEmpty()) throw new BankException(ErrorCode.ALERT_NOT_FOUND, String.valueOf(alertId));
            String current = (String) rows.get(0).get("status");
            if ("CLOSED".equals(current) || "REPORTED".equals(current)) {
                throw new BankException(ErrorCode.VALIDATION_ERROR);
            }
            execute(c, "UPDATE aml_alert SET status = ?, assigned_to = ?, resolution_note = ?, updated_at = ? WHERE alert_id = ?",
                    status, by, note, LocalDateTime.now(clock), alertId);
            return query(c, "SELECT * FROM aml_alert WHERE alert_id = ?", alertId).get(0);
        });
    }
}
