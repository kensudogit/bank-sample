package com.example.bank.service;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.support.DataSources;
import com.example.bank.support.Transactions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import static com.example.bank.repository.ProductRepository.execute;
import static com.example.bank.repository.ProductRepository.query;

/**
 * 課金の可視化とコスト予測（FinOps）。
 *
 * <ul>
 *   <li>使用量の記録：Kong の全要求（http-log）と、AI Agent の Claude 呼び出し（トークン数）を usage_event に、単価表（rate_card）で
 *       円に換算して記録する。1 要求の費用 = ゲートウェイ ＋（MuleSoft）＋（勘定系）。LLM = 入力・出力・キャッシュのトークン × 単価 × 為替</li>
 *   <li>配賦：Kong の利用者・Agent・経路（画面・MuleSoft）を cost_center で部門と予算に結び付ける</li>
 *   <li>予測：月末予測 = 当月実績 ＋ 直近 7 日の日平均 × 残り日数 ＋ 固定費。データが 7 日未満なら信頼度「低」。
 *       幅（直近 30 日の日平均・直近の最大日 × 残り日数）も示す</li>
 *   <li>予算：見込みが予算の 80% で注意、100% で超過。hard_stop の配賦先（Agent）は、当月実績が予算に達したら新しい実行を止める</li>
 *   <li>インフラ：Railway の使用量（vCPU・メモリ・通信・ディスク）を日ごと・サービスごとに取り込み、単価で換算する</li>
 * </ul>
 * 単価は仮置き（rate_card）。ベンダーとの契約と請求書で照合して更新すること。
 */
public class FinOpsService {
    private static final Logger LOG = LogManager.getLogger(FinOpsService.class);
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    /** MuleSoft を通る Kong のサービス */
    private static final List<String> MULE_SERVICES = Arrays.asList("integration-zengin", "integration-billing", "integration-fx",
            "integration-health", "integration-runs");
    /** 勘定系（Liberty）に届く Kong のサービス */
    private static final List<String> CORE_SERVICES = Arrays.asList("bank-core-api", "agent-core-api", "agent-meter-usage", "agent-meter-budget");

    /** Railway の API を呼ぶ（テストでは差し替える）。引数は GraphQL の要求本文（JSON）、戻り値は応答本文 */
    public interface GraphQlClient {
        String post(String body) throws java.io.IOException;
    }

    private static final FinOpsService DEFAULT = new FinOpsService(DataSources::bank, Clock.systemDefaultZone(), railwayFromEnv());

    private final Supplier<DataSource> dataSource;
    private final Clock clock;
    private final GraphQlClient railway;
    private final String railwayProjectId;

    public FinOpsService(Supplier<DataSource> dataSource, Clock clock, GraphQlClient railway) {
        this(dataSource, clock, railway, System.getenv("RAILWAY_PROJECT_ID"));
    }

    public FinOpsService(Supplier<DataSource> dataSource, Clock clock, GraphQlClient railway, String railwayProjectId) {
        this.dataSource = dataSource;
        this.clock = clock;
        this.railway = railway;
        this.railwayProjectId = railwayProjectId;
    }

    public static FinOpsService getDefault() { return DEFAULT; }

    // ===================== 単価 =====================

    /** 単価表（rate_key → [unit, unit_price, currency]）。 */
    Map<String, Object[]> rates(Connection c) throws SQLException {
        Map<String, Object[]> m = new HashMap<>();
        for (Map<String, Object> r : query(c, "SELECT rate_key, unit, unit_price, currency FROM rate_card")) {
            m.put((String) r.get("rateKey"), new Object[]{r.get("unit"), num(r.get("unitPrice")), r.get("currency")});
        }
        return m;
    }

    private static BigDecimal priceJpy(Map<String, Object[]> rates, String key) {
        Object[] r = rates.get(key);
        if (r == null) return BigDecimal.ZERO;
        BigDecimal p = (BigDecimal) r[1];
        return "USD".equals(r[2]) ? p.multiply(fx(rates)) : p;
    }

    private static BigDecimal fx(Map<String, Object[]> rates) {
        Object[] r = rates.get("FX_USDJPY");
        return r == null ? new BigDecimal("150") : (BigDecimal) r[1];
    }

    /** 単価表の一覧（画面の表示用）。 */
    public List<Map<String, Object>> rateCard() {
        return Transactions.readOnly(dataSource.get(), c -> query(c, "SELECT * FROM rate_card ORDER BY rate_key"));
    }

    // ===================== Kong の全要求（http-log） =====================

    /**
     * Kong の http-log の 1 回分（1 件または配列）を記録する。死活確認と、経路が決まらなかった要求は記録しない。
     *
     * @param entries Kong のログ（Map）の一覧
     * @return 記録した件数
     */
    public int ingestGateway(List<?> entries) {
        if (entries == null || entries.size() > 500) throw new BankException(ErrorCode.VALIDATION_ERROR);
        return Transactions.inTransaction(dataSource.get(), c -> {
            Map<String, Object[]> rates = rates(c);
            Map<String, String> agents = subjectsOfType(c, "AGENT");
            int n = 0;
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO usage_event (occurred_at, source, api_id, subject_type, "
                    + "subject_id, principal, status, latency_ms, request_count, cost_jpy, request_id) VALUES (?, 'GATEWAY', ?, ?, ?, ?, ?, ?, 1, ?, ?)")) {
                for (Object o : entries) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> e = (Map<?, ?>) o;
                    String service = str(path(e, "service", "name"));
                    if (service == null || "gateway-health".equals(service) || !service.matches("^[A-Za-z0-9_.-]{1,60}$")) continue;
                    String consumer = str(path(e, "consumer", "username"));
                    String caller = str(path(e, "bank", "caller"));
                    String[] subject = subjectOf(service, consumer, caller, agents);
                    BigDecimal cost = priceJpy(rates, "GATEWAY_REQUEST");
                    if (MULE_SERVICES.contains(service)) cost = cost.add(priceJpy(rates, "MULE_REQUEST"));
                    if (CORE_SERVICES.contains(service)) cost = cost.add(priceJpy(rates, "CORE_REQUEST"));
                    Object started = e.get("started_at");
                    long ms = started instanceof Number ? ((Number) started).longValue() : clock.millis();
                    ps.setTimestamp(1, Timestamp.valueOf(LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), JST)));
                    ps.setString(2, service);
                    ps.setString(3, subject[0]);
                    ps.setString(4, subject[1]);
                    ps.setString(5, clip(str(path(e, "bank", "user")), 64));
                    Object status = path(e, "response", "status");
                    if (status instanceof Number) ps.setInt(6, ((Number) status).intValue()); else ps.setNull(6, java.sql.Types.INTEGER);
                    Object latency = path(e, "latencies", "request");
                    if (latency instanceof Number) ps.setLong(7, ((Number) latency).longValue()); else ps.setNull(7, java.sql.Types.INTEGER);
                    ps.setBigDecimal(8, cost);
                    ps.setString(9, clip(str(path(e, "bank", "request_id")), 64));
                    ps.addBatch();
                    n++;
                }
                if (n > 0) ps.executeBatch();
            }
            return n;
        });
    }

    // 配賦先：Agent の利用者 → AGENT、ほかの Kong の利用者 → CONSUMER、利用者なし → 経路（MuleSoft からの勘定系呼び出し・画面）
    static String[] subjectOf(String service, String consumer, String caller, Map<String, String> agents) {
        if (consumer != null && agents.containsKey(consumer)) return new String[]{"AGENT", consumer};
        if (consumer != null) return new String[]{"CONSUMER", consumer};
        if ("mule".equals(caller)) return new String[]{"CHANNEL", "integration"};
        return new String[]{"CHANNEL", "web"};
    }

    private static Map<String, String> subjectsOfType(Connection c, String type) throws SQLException {
        Map<String, String> m = new HashMap<>();
        for (Map<String, Object> r : query(c, "SELECT subject_id, department FROM cost_center WHERE subject_type = ?", type)) {
            m.put((String) r.get("subjectId"), (String) r.get("department"));
        }
        return m;
    }

    // ===================== AI Agent =====================

    /** Agent の 1 回の実行の記録。 */
    public static final class AgentRun {
        public String runId;
        public String principal;
        public String principalOid;
        public String status;
        public String model;
        public int steps;
        public String toolCalls;
        public int deniedCalls;
        public long inputTokens;
        public long outputTokens;
        public long cacheReadTokens;
        public long cacheWriteTokens;
        public String promptSha256;
        public String traceId;
        public String summary;
        public Long startedAtEpochMs;
    }

    /**
     * Agent の実行と LLM の使用量を記録する。Agent の ID はトークン（Kong が検証した azp）から渡され、要求本文の値は使わない。
     *
     * @return 記録した実行（費用を含む）
     */
    public Map<String, Object> recordAgentRun(String agentId, AgentRun r) {
        if (agentId == null || r == null || r.runId == null || !r.runId.matches("^[A-Za-z0-9-]{8,36}$")
                || r.principal == null || !r.principal.matches("^[A-Za-z0-9_.@-]{1,64}$")
                || r.model == null || !r.model.matches("^[a-z0-9.-]{1,40}$")
                || !Arrays.asList("COMPLETED", "FAILED", "BLOCKED", "LIMITED").contains(r.status)
                || r.inputTokens < 0 || r.outputTokens < 0 || r.cacheReadTokens < 0 || r.cacheWriteTokens < 0 || r.steps < 0) {
            throw new BankException(ErrorCode.VALIDATION_ERROR);
        }
        return Transactions.inTransaction(dataSource.get(), c -> {
            Map<String, Object[]> rates = rates(c);
            BigDecimal cost = llmCostJpy(rates, r.model, r.inputTokens, r.outputTokens, r.cacheReadTokens, r.cacheWriteTokens);
            LocalDateTime now = LocalDateTime.now(clock);
            LocalDateTime started = r.startedAtEpochMs == null ? now : LocalDateTime.ofInstant(Instant.ofEpochMilli(r.startedAtEpochMs), clock.getZone());
            execute(c, "INSERT INTO agent_run (run_id, agent_id, principal, principal_oid, started_at, finished_at, status, model, steps, "
                    + "tool_calls, denied_calls, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, cost_jpy, prompt_sha256, "
                    + "trace_id, summary) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    r.runId, agentId, r.principal, clip(r.principalOid, 36), started, now, r.status, r.model, r.steps,
                    clip(r.toolCalls, 1000), r.deniedCalls, r.inputTokens, r.outputTokens, r.cacheReadTokens, r.cacheWriteTokens, cost,
                    r.promptSha256 != null && r.promptSha256.matches("^[0-9a-f]{64}$") ? r.promptSha256 : null,
                    r.traceId != null && r.traceId.matches("^[0-9a-f]{32}$") ? r.traceId : null, clip(r.summary, 400));
            if (r.inputTokens + r.outputTokens + r.cacheReadTokens + r.cacheWriteTokens > 0) {
                execute(c, "INSERT INTO usage_event (occurred_at, source, api_id, subject_type, subject_id, principal, request_count, model, "
                        + "input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, cost_jpy, run_id) "
                        + "VALUES (?, 'LLM', ?, 'AGENT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        now, "llm:" + r.model, agentId, r.principal, Math.max(1, r.steps), r.model, r.inputTokens, r.outputTokens,
                        r.cacheReadTokens, r.cacheWriteTokens, cost, r.runId);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("runId", r.runId);
            out.put("costJpy", cost.setScale(2, RoundingMode.HALF_UP));
            return out;
        });
    }

    /** LLM の費用（円）。単価表にないモデルは 0 円にせず、拒否する（費用の取りこぼしを防ぐ）。 */
    static BigDecimal llmCostJpy(Map<String, Object[]> rates, String model, long in, long out, long cacheRead, long cacheWrite) {
        String p = "LLM:" + model + ":";
        if (!rates.containsKey(p + "INPUT") || !rates.containsKey(p + "OUTPUT")) throw new BankException(ErrorCode.VALIDATION_ERROR);
        BigDecimal mtok = new BigDecimal(1_000_000);
        return priceJpy(rates, p + "INPUT").multiply(BigDecimal.valueOf(in))
                .add(priceJpy(rates, p + "OUTPUT").multiply(BigDecimal.valueOf(out)))
                .add(priceJpy(rates, p + "CACHE_READ").multiply(BigDecimal.valueOf(cacheRead)))
                .add(priceJpy(rates, p + "CACHE_WRITE").multiply(BigDecimal.valueOf(cacheWrite)))
                .divide(mtok, 6, RoundingMode.HALF_UP);
    }

    /**
     * Agent の予算の状況。当月の費用（その Agent の API 呼び出し ＋ LLM）と予算、新しい実行を許すか。
     */
    public Map<String, Object> agentBudget(String agentId) {
        return Transactions.readOnly(dataSource.get(), c -> {
            LocalDate today = LocalDate.now(clock);
            LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
            List<Map<String, Object>> cc = query(c, "SELECT * FROM cost_center WHERE subject_type = 'AGENT' AND subject_id = ?", agentId);
            BigDecimal mtd = num(query(c, "SELECT COALESCE(SUM(cost_jpy), 0) AS v FROM usage_event WHERE subject_type = 'AGENT' "
                    + "AND subject_id = ? AND occurred_at >= ?", agentId, monthStart).get(0).get("v"));
            BigDecimal todayCost = num(query(c, "SELECT COALESCE(SUM(cost_jpy), 0) AS v FROM usage_event WHERE subject_type = 'AGENT' "
                    + "AND subject_id = ? AND occurred_at >= ?", agentId, today.atStartOfDay()).get(0).get("v"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("agentId", agentId);
            m.put("month", YearMonth.from(today).toString());
            m.put("monthToDateJpy", mtd.setScale(2, RoundingMode.HALF_UP));
            m.put("todayJpy", todayCost.setScale(2, RoundingMode.HALF_UP));
            if (cc.isEmpty()) {
                // 配賦先が登録されていない Agent は動かさない（費用の持ち主が決まっていない）
                m.put("budgetJpy", 0);
                m.put("allowed", false);
                m.put("reason", "配賦先（コストセンター）が登録されていません");
                return m;
            }
            BigDecimal budget = num(cc.get(0).get("monthlyBudgetJpy"));
            boolean hardStop = num(cc.get(0).get("hardStop")).intValue() == 1;
            m.put("budgetJpy", budget);
            m.put("department", cc.get(0).get("department"));
            m.put("hardStop", hardStop);
            boolean over = mtd.compareTo(budget) >= 0;
            m.put("allowed", !(hardStop && over));
            m.put("reason", hardStop && over ? "当月の予算に達したため、新しい実行を止めています" : null);
            return m;
        });
    }

    /** Agent の実行の一覧（新しい順・最大 100 件）。 */
    public List<Map<String, Object>> agentRuns() {
        return Transactions.readOnly(dataSource.get(), c -> query(c, "SELECT run_id, agent_id, principal, started_at, finished_at, status, "
                + "model, steps, tool_calls, denied_calls, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, cost_jpy, "
                + "trace_id, summary FROM agent_run ORDER BY started_at DESC FETCH FIRST 100 ROWS ONLY"));
    }

    // ===================== 集計と予測 =====================

    /** 予測の結果。 */
    public static final class Forecast {
        public final BigDecimal monthToDate;
        public final BigDecimal forecast;
        public final BigDecimal low;
        public final BigDecimal high;
        public final String confidence;
        public final boolean anomaly;

        Forecast(BigDecimal mtd, BigDecimal forecast, BigDecimal low, BigDecimal high, String confidence, boolean anomaly) {
            this.monthToDate = mtd;
            this.forecast = forecast;
            this.low = low;
            this.high = high;
            this.confidence = confidence;
            this.anomaly = anomaly;
        }
    }

    /**
     * 月末の予測。daily は当月 1 日から今日までの日別の費用（今日を含む。今日は途中まで）。
     * 予測 = 当月実績 ＋ 直近 7 日（今日を除く完了日）の日平均 × 残り日数（今日の残りを含めて日数で数える）＋ 固定費。
     * 下限は直近 30 日の日平均、上限は直近の最大日を使う。完了日が 7 日未満なら信頼度「低」、14 日未満なら「中」。
     *
     * @param daily        日別の費用（古い順。最後が今日）
     * @param history      当月より前を含む、今日を除く直近の完了日の費用（古い順。最大 30 日）
     * @param daysInMonth  当月の日数
     * @param fixed        当月の固定費（配賦済み）
     */
    static Forecast forecast(List<BigDecimal> daily, List<BigDecimal> history, int daysInMonth, BigDecimal fixed) {
        BigDecimal mtd = daily.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        int elapsed = daily.size();
        // 今日の残り（0.5 日と見なす）＋ 明日以降
        BigDecimal remaining = new BigDecimal(daysInMonth - elapsed).add(new BigDecimal("0.5"));
        List<BigDecimal> last7 = history.subList(Math.max(0, history.size() - 7), history.size());
        BigDecimal avg7 = avg(last7);
        BigDecimal avg30 = avg(history);
        BigDecimal peak = last7.stream().reduce(BigDecimal.ZERO, BigDecimal::max);
        BigDecimal f = mtd.add(avg7.multiply(remaining)).add(fixed);
        BigDecimal low = mtd.add(avg30.min(avg7).multiply(remaining)).add(fixed);
        BigDecimal high = mtd.add(peak.max(avg7).multiply(remaining)).add(fixed);
        String confidence = history.size() < 7 ? "低" : history.size() < 14 ? "中" : "高";
        BigDecimal today = daily.isEmpty() ? BigDecimal.ZERO : daily.get(daily.size() - 1);
        boolean anomaly = avg7.signum() > 0 && today.compareTo(avg7.multiply(new BigDecimal(3))) > 0 && today.compareTo(new BigDecimal(100)) > 0;
        return new Forecast(scale(mtd), scale(f), scale(low), scale(high), confidence, anomaly);
    }

    private static BigDecimal avg(List<BigDecimal> list) {
        if (list.isEmpty()) return BigDecimal.ZERO;
        return list.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(new BigDecimal(list.size()), 6, RoundingMode.HALF_UP);
    }

    /**
     * 当月（または指定月）の課金の可視化：合計・部門別・配賦先別・API 別・日別、Agent・インフラの内訳、予測と予算。
     *
     * @param month 対象月（null は当月。過去の月は実績だけで予測しない）
     */
    public Map<String, Object> summary(YearMonth month) {
        LocalDate today = LocalDate.now(clock);
        YearMonth ym = month == null ? YearMonth.from(today) : month;
        if (ym.isAfter(YearMonth.from(today))) throw new BankException(ErrorCode.INVALID_PERIOD);
        boolean current = ym.equals(YearMonth.from(today));
        LocalDate first = ym.atDay(1);
        LocalDate last = current ? today : ym.atEndOfMonth();
        return Transactions.readOnly(dataSource.get(), c -> {
            Map<String, Object[]> rates = rates(c);
            // 日別・配賦先別の費用（当月より前 30 日分も、予測の履歴として読む）
            LocalDate historyFrom = first.minusDays(30);
            Map<String, Map<LocalDate, BigDecimal>> bySubjectDay = new TreeMap<>();
            Map<String, long[]> counters = new HashMap<>();
            for (Map<String, Object> r : query(c, "SELECT subject_type, subject_id, TRUNC(occurred_at) AS d, SUM(cost_jpy) AS cost, "
                    + "SUM(request_count) AS reqs, SUM(input_tokens + output_tokens + cache_read_tokens + cache_write_tokens) AS tokens, "
                    + "source FROM usage_event WHERE occurred_at >= ? AND occurred_at < ? GROUP BY subject_type, subject_id, TRUNC(occurred_at), source",
                    historyFrom.atStartOfDay(), last.plusDays(1).atStartOfDay())) {
                String key = r.get("subjectType") + ":" + r.get("subjectId");
                LocalDate d = LocalDate.parse(String.valueOf(r.get("d")).substring(0, 10));
                bySubjectDay.computeIfAbsent(key, k -> new TreeMap<>()).merge(d, num(r.get("cost")), BigDecimal::add);
                if (!d.isBefore(first)) {
                    long[] cnt = counters.computeIfAbsent(key, k -> new long[2]);
                    if ("GATEWAY".equals(r.get("source"))) cnt[0] += num(r.get("reqs")).longValue();
                    cnt[1] += num(r.get("tokens")).longValue();
                }
            }
            // インフラ（Railway）も配賦先 INFRA:railway として同じ予測にかける
            for (Map<String, Object> r : query(c, "SELECT cost_date AS d, SUM(cost_jpy) AS cost FROM infra_cost_daily "
                    + "WHERE cost_date >= ? AND cost_date <= ? GROUP BY cost_date", historyFrom, last)) {
                LocalDate d = LocalDate.parse(String.valueOf(r.get("d")).substring(0, 10));
                bySubjectDay.computeIfAbsent("INFRA:railway", k -> new TreeMap<>()).merge(d, num(r.get("cost")), BigDecimal::add);
            }
            Map<String, Map<String, Object>> centers = new HashMap<>();
            for (Map<String, Object> r : query(c, "SELECT * FROM cost_center")) centers.put(r.get("subjectType") + ":" + r.get("subjectId"), r);
            for (String k : centers.keySet()) bySubjectDay.computeIfAbsent(k, x -> new TreeMap<>());

            BigDecimal fixedTotal = BigDecimal.ZERO;
            for (Map.Entry<String, Object[]> e : rates.entrySet()) if (e.getKey().startsWith("FIXED:")) fixedTotal = fixedTotal.add(priceJpy(rates, e.getKey()));

            List<Map<String, Object>> subjects = new ArrayList<>();
            Map<String, BigDecimal[]> departments = new TreeMap<>();
            BigDecimal[] total = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
            boolean lowConfidence = false;
            for (Map.Entry<String, Map<LocalDate, BigDecimal>> e : bySubjectDay.entrySet()) {
                List<BigDecimal> daily = new ArrayList<>();
                for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) daily.add(e.getValue().getOrDefault(d, BigDecimal.ZERO));
                LocalDate firstSeen = e.getValue().isEmpty() ? today : Collections.min(e.getValue().keySet());
                List<BigDecimal> history = new ArrayList<>();
                for (LocalDate d = today.minusDays(30); d.isBefore(today); d = d.plusDays(1)) {
                    if (!d.isBefore(firstSeen)) history.add(e.getValue().getOrDefault(d, BigDecimal.ZERO));
                }
                Forecast f = current ? forecast(daily, history, ym.lengthOfMonth(), BigDecimal.ZERO)
                        : new Forecast(scale(sum(daily)), scale(sum(daily)), scale(sum(daily)), scale(sum(daily)), "確定", false);
                Map<String, Object> cc = centers.get(e.getKey());
                String department = cc == null ? "未配賦" : (String) cc.get("department");
                BigDecimal budget = cc == null ? BigDecimal.ZERO : num(cc.get("monthlyBudgetJpy"));
                String[] key = e.getKey().split(":", 2);
                long[] cnt = counters.getOrDefault(e.getKey(), new long[2]);
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("subjectType", key[0]);
                s.put("subjectId", key[1]);
                s.put("department", department);
                s.put("owner", cc == null ? null : cc.get("owner"));
                s.put("requests", cnt[0]);
                s.put("tokens", cnt[1]);
                s.put("monthToDateJpy", f.monthToDate);
                s.put("forecastJpy", f.forecast);
                s.put("forecastLowJpy", f.low);
                s.put("forecastHighJpy", f.high);
                s.put("confidence", f.confidence);
                s.put("anomaly", f.anomaly);
                s.put("budgetJpy", budget);
                s.put("hardStop", cc != null && num(cc.get("hardStop")).intValue() == 1);
                s.put("budgetStatus", budgetStatus(f.forecast, budget));
                subjects.add(s);
                if ("低".equals(f.confidence) && f.monthToDate.signum() > 0) lowConfidence = true;
                BigDecimal[] dep = departments.computeIfAbsent(department, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                dep[0] = dep[0].add(f.monthToDate);
                dep[1] = dep[1].add(f.forecast);
                dep[2] = dep[2].add(budget);
                total[0] = total[0].add(f.monthToDate);
                total[1] = total[1].add(f.forecast);
                total[2] = total[2].add(budget);
                total[3] = total[3].add(f.low);
                total[4] = total[4].add(f.high);
            }
            subjects.sort((a, b) -> ((BigDecimal) b.get("forecastJpy")).compareTo((BigDecimal) a.get("forecastJpy")));
            // 固定費は当月の見込みに一度だけ加える（部門への配賦は利用の割合で按分する）
            BigDecimal fixed = current || fixedTotal.signum() > 0 ? scale(fixedTotal) : BigDecimal.ZERO;
            List<Map<String, Object>> deps = new ArrayList<>();
            for (Map.Entry<String, BigDecimal[]> d : departments.entrySet()) {
                BigDecimal share = total[1].signum() == 0 ? BigDecimal.ZERO : d.getValue()[1].divide(total[1], 6, RoundingMode.HALF_UP);
                BigDecimal fc = scale(d.getValue()[1].add(fixed.multiply(share)));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("department", d.getKey());
                m.put("monthToDateJpy", scale(d.getValue()[0]));
                m.put("forecastJpy", fc);
                m.put("budgetJpy", scale(d.getValue()[2]));
                m.put("budgetStatus", budgetStatus(fc, d.getValue()[2]));
                deps.add(m);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("month", ym.toString());
            out.put("asOf", today.toString());
            out.put("current", current);
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("monthToDateJpy", scale(total[0]));
            t.put("forecastJpy", scale(total[1].add(fixed)));
            t.put("forecastLowJpy", scale(total[3].add(fixed)));
            t.put("forecastHighJpy", scale(total[4].add(fixed)));
            t.put("fixedJpy", fixed);
            t.put("budgetJpy", scale(total[2]));
            t.put("budgetStatus", budgetStatus(total[1].add(fixed), total[2]));
            t.put("lowConfidence", lowConfidence);
            out.put("total", t);
            out.put("bySource", bySource(c, first, last));
            out.put("byDepartment", deps);
            out.put("bySubject", subjects);
            out.put("byApi", query(c, "SELECT api_id, source, SUM(request_count) AS requests, AVG(latency_ms) AS avg_latency_ms, "
                    + "SUM(CASE WHEN status >= 500 THEN 1 ELSE 0 END) AS errors, SUM(cost_jpy) AS cost_jpy FROM usage_event "
                    + "WHERE occurred_at >= ? AND occurred_at < ? GROUP BY api_id, source ORDER BY 6 DESC",
                    first.atStartOfDay(), last.plusDays(1).atStartOfDay()));
            out.put("daily", daily(c, first, last));
            out.put("infra", query(c, "SELECT service_name, SUM(cpu_vcpu_min) AS cpu_vcpu_min, SUM(memory_gb_min) AS memory_gb_min, "
                    + "SUM(egress_gb) AS egress_gb, SUM(cost_usd) AS cost_usd, SUM(cost_jpy) AS cost_jpy, MAX(fetched_at) AS fetched_at "
                    + "FROM infra_cost_daily WHERE cost_date >= ? AND cost_date <= ? GROUP BY service_name ORDER BY 6 DESC", first, last));
            Map<String, Object> assumptions = new LinkedHashMap<>();
            assumptions.put("fxUsdJpy", fx(rates));
            assumptions.put("method", "月末予測 = 当月実績 ＋ 直近 7 日の日平均 × 残り日数 ＋ 固定費。幅は直近 30 日の日平均と直近の最大日");
            assumptions.put("note", "単価は仮置き（単価表）。ベンダーの請求書と照合して更新すること。税は含まない");
            out.put("assumptions", assumptions);
            return out;
        });
    }

    private static String budgetStatus(BigDecimal forecast, BigDecimal budget) {
        if (budget.signum() == 0) return forecast.signum() > 0 ? "NO_BUDGET" : "OK";
        BigDecimal ratio = forecast.divide(budget, 4, RoundingMode.HALF_UP);
        if (ratio.compareTo(BigDecimal.ONE) > 0) return "OVER";
        if (ratio.compareTo(new BigDecimal("0.8")) >= 0) return "WARN";
        return "OK";
    }

    private List<Map<String, Object>> bySource(Connection c, LocalDate first, LocalDate last) throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>(query(c, "SELECT source, SUM(request_count) AS requests, "
                + "SUM(input_tokens) AS input_tokens, SUM(output_tokens) AS output_tokens, SUM(cache_read_tokens) AS cache_read_tokens, "
                + "SUM(cost_jpy) AS cost_jpy FROM usage_event WHERE occurred_at >= ? AND occurred_at < ? GROUP BY source",
                first.atStartOfDay(), last.plusDays(1).atStartOfDay()));
        Map<String, Object> infra = new LinkedHashMap<>();
        infra.put("source", "INFRA");
        infra.put("costJpy", num(query(c, "SELECT COALESCE(SUM(cost_jpy), 0) AS v FROM infra_cost_daily WHERE cost_date >= ? AND cost_date <= ?",
                first, last).get(0).get("v")));
        list.add(infra);
        return list;
    }

    private List<Map<String, Object>> daily(Connection c, LocalDate first, LocalDate last) throws SQLException {
        Map<LocalDate, Map<String, Object>> days = new TreeMap<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", d.toString());
            m.put("gatewayJpy", BigDecimal.ZERO);
            m.put("llmJpy", BigDecimal.ZERO);
            m.put("infraJpy", BigDecimal.ZERO);
            days.put(d, m);
        }
        for (Map<String, Object> r : query(c, "SELECT TRUNC(occurred_at) AS d, source, SUM(cost_jpy) AS cost FROM usage_event "
                + "WHERE occurred_at >= ? AND occurred_at < ? GROUP BY TRUNC(occurred_at), source", first.atStartOfDay(), last.plusDays(1).atStartOfDay())) {
            Map<String, Object> m = days.get(LocalDate.parse(String.valueOf(r.get("d")).substring(0, 10)));
            if (m != null) m.put("GATEWAY".equals(r.get("source")) ? "gatewayJpy" : "llmJpy", scale(num(r.get("cost"))));
        }
        for (Map<String, Object> r : query(c, "SELECT cost_date AS d, SUM(cost_jpy) AS cost FROM infra_cost_daily WHERE cost_date >= ? "
                + "AND cost_date <= ? GROUP BY cost_date", first, last)) {
            Map<String, Object> m = days.get(LocalDate.parse(String.valueOf(r.get("d")).substring(0, 10)));
            if (m != null) m.put("infraJpy", scale(num(r.get("cost"))));
        }
        return new ArrayList<>(days.values());
    }

    /** 配賦先の予算を変更する（役席）。 */
    public Map<String, Object> updateBudget(String subjectType, String subjectId, long budgetJpy, String by) {
        if (budgetJpy < 0 || budgetJpy > 100_000_000L) throw new BankException(ErrorCode.VALIDATION_ERROR);
        return Transactions.inTransaction(dataSource.get(), c -> {
            if (execute(c, "UPDATE cost_center SET monthly_budget_jpy = ? WHERE subject_type = ? AND subject_id = ?", budgetJpy, subjectType, subjectId) != 1) {
                throw new BankException(ErrorCode.VALIDATION_ERROR);
            }
            LOG.info("{\"event\":\"budget-updated\",\"subject\":\"{}:{}\",\"budgetJpy\":{},\"by\":\"{}\"}", subjectType, subjectId, budgetJpy, by);
            return query(c, "SELECT * FROM cost_center WHERE subject_type = ? AND subject_id = ?", subjectType, subjectId).get(0);
        });
    }

    // ===================== インフラ（Railway） =====================

    /**
     * Railway の使用量を日ごと・サービスごとに取り込む（from〜to の各日。今日は途中まで）。
     * RAILWAY_API_TOKEN（アカウント／ワークスペースのトークン）と RAILWAY_PROJECT_ID が未設定なら何もしない。
     *
     * @return status（SYNCED / NOT_CONFIGURED / FAILED）と取り込んだ日数・行数
     */
    public Map<String, Object> syncInfra(LocalDate from, LocalDate to) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (railway == null || railwayProjectId == null || railwayProjectId.isEmpty()) {
            out.put("status", "NOT_CONFIGURED");
            out.put("message", "RAILWAY_API_TOKEN と RAILWAY_PROJECT_ID を設定してください");
            return out;
        }
        if (from == null || to == null || from.isAfter(to) || from.isBefore(to.minusDays(40))) throw new BankException(ErrorCode.INVALID_PERIOD);
        try {
            Map<String, String> names = railwayServiceNames();
            int rows = 0;
            int days = 0;
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                Map<String, double[]> usage = railwayUsage(d);
                final LocalDate day = d;
                rows += Transactions.inTransaction(dataSource.get(), c -> {
                    Map<String, Object[]> rates = rates(c);
                    BigDecimal fx = fx(rates);
                    int n = 0;
                    execute(c, "DELETE FROM infra_cost_daily WHERE cost_date = ?", day);
                    for (Map.Entry<String, double[]> e : usage.entrySet()) {
                        double[] v = e.getValue();
                        BigDecimal usd = usd(rates, "RAILWAY:CPU", v[0]).add(usd(rates, "RAILWAY:MEMORY", v[1])).add(usd(rates, "RAILWAY:EGRESS", v[2]))
                                // ディスクは GB・分の積算として、1 か月（30 日）あたりの単価で換算する
                                .add(usd(rates, "RAILWAY:DISK", v[3] / (60 * 24 * 30)));
                        execute(c, "INSERT INTO infra_cost_daily (cost_date, service_name, cpu_vcpu_min, memory_gb_min, egress_gb, disk_gb, "
                                + "cost_usd, cost_jpy, fetched_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", day,
                                clip(names.getOrDefault(e.getKey(), e.getKey()), 60), BigDecimal.valueOf(v[0]), BigDecimal.valueOf(v[1]),
                                BigDecimal.valueOf(v[2]), BigDecimal.valueOf(v[3]), usd.setScale(6, RoundingMode.HALF_UP),
                                usd.multiply(fx).setScale(6, RoundingMode.HALF_UP), LocalDateTime.now(clock));
                        n++;
                    }
                    return n;
                });
                days++;
            }
            out.put("status", "SYNCED");
            out.put("days", days);
            out.put("rows", rows);
        } catch (java.io.IOException | RuntimeException e) {
            if (e instanceof BankException) throw (BankException) e;
            LOG.warn("railway usage sync failed: {}", e.toString());
            out.put("status", "FAILED");
            out.put("message", "Railway の API から取得できませんでした（" + e.getClass().getSimpleName() + "）");
        }
        return out;
    }

    private static BigDecimal usd(Map<String, Object[]> rates, String key, double quantity) {
        Object[] r = rates.get(key);
        return r == null ? BigDecimal.ZERO : ((BigDecimal) r[1]).multiply(BigDecimal.valueOf(quantity));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> railwayServiceNames() throws java.io.IOException {
        String body = "{\"query\":\"query($p:String!){ project(id:$p){ services { edges { node { id name } } } } }\",\"variables\":{\"p\":\""
                + railwayProjectId + "\"}}";
        Map<String, String> names = new HashMap<>();
        Object data = graphql(body);
        Object edges = path((Map<?, ?>) data, "project", "services", "edges");
        if (edges instanceof List) {
            for (Object e : (List<Object>) edges) {
                Object node = e instanceof Map ? ((Map<?, ?>) e).get("node") : null;
                if (node instanceof Map) names.put(str(((Map<?, ?>) node).get("id")), str(((Map<?, ?>) node).get("name")));
            }
        }
        return names;
    }

    // サービス ID → [vCPU・分, GB・分, 外向き GB, ディスク GB・分]
    @SuppressWarnings("unchecked")
    private Map<String, double[]> railwayUsage(LocalDate day) throws java.io.IOException {
        String start = day.atStartOfDay(JST).toInstant().toString();
        String end = day.plusDays(1).atStartOfDay(JST).toInstant().toString();
        String body = "{\"query\":\"query($p:String!,$s:DateTime!,$e:DateTime!){ usage(projectId:$p, measurements:[CPU_USAGE,MEMORY_USAGE_GB,"
                + "NETWORK_TX_GB,DISK_USAGE_GB], startDate:$s, endDate:$e, groupBy:[SERVICE_ID]){ measurement value tags { serviceId } } }\","
                + "\"variables\":{\"p\":\"" + railwayProjectId + "\",\"s\":\"" + start + "\",\"e\":\"" + end + "\"}}";
        Map<String, double[]> m = new TreeMap<>();
        Object list = ((Map<?, ?>) graphql(body)).get("usage");
        if (list instanceof List) {
            for (Object o : (List<Object>) list) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> u = (Map<?, ?>) o;
                String id = str(path(u, "tags", "serviceId"));
                if (id == null) continue;
                double v = u.get("value") instanceof Number ? ((Number) u.get("value")).doubleValue() : 0;
                int i = Arrays.asList("CPU_USAGE", "MEMORY_USAGE_GB", "NETWORK_TX_GB", "DISK_USAGE_GB").indexOf(str(u.get("measurement")));
                if (i >= 0) m.computeIfAbsent(id, k -> new double[4])[i] += v;
            }
        }
        return m;
    }

    private Object graphql(String body) throws java.io.IOException {
        String text = railway.post(body);
        Object json;
        try {
            json = new org.apache.struts2.json.JSONReader().read(text);
        } catch (org.apache.struts2.json.JSONException e) {
            throw new java.io.IOException("invalid response", e);
        }
        if (!(json instanceof Map)) throw new java.io.IOException("invalid response");
        Object errors = ((Map<?, ?>) json).get("errors");
        if (errors instanceof List && !((List<?>) errors).isEmpty()) {
            throw new java.io.IOException("railway: " + str(((Map<?, ?>) ((List<?>) errors).get(0)).get("message")));
        }
        Object data = ((Map<?, ?>) json).get("data");
        if (!(data instanceof Map)) throw new java.io.IOException("no data");
        return data;
    }

    /** 環境変数から Railway の API クライアントを作る（トークンがなければ null） */
    static GraphQlClient railwayFromEnv() {
        String token = System.getenv("RAILWAY_API_TOKEN");
        if (token == null || token.trim().isEmpty()) return null;
        String url = System.getenv().getOrDefault("RAILWAY_API_URL", "https://backboard.railway.com/graphql/v2");
        return body -> {
            java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(5000);
            con.setReadTimeout(20000);
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json");
            con.setRequestProperty("Authorization", "Bearer " + token.trim());
            try (java.io.OutputStream os = con.getOutputStream()) {
                os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            int code = con.getResponseCode();
            java.io.InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
            if (in == null) throw new java.io.IOException("HTTP " + code);
            try (java.util.Scanner s = new java.util.Scanner(in, "UTF-8").useDelimiter("\\A")) {
                String text = s.hasNext() ? s.next() : "";
                if (code >= 400 && !text.contains("\"errors\"")) throw new java.io.IOException("HTTP " + code);
                return text;
            }
        };
    }

    // ===================== helpers =====================

    private static Object path(Map<?, ?> m, String... keys) {
        Object cur = m;
        for (String k : keys) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<?, ?>) cur).get(k);
        }
        return cur;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    static BigDecimal num(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        return new BigDecimal(o.toString());
    }

    private static BigDecimal sum(List<BigDecimal> list) {
        return list.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
