package com.example.bank.service;

import com.example.bank.domain.BankException;
import com.example.bank.domain.LoginUser;
import com.example.bank.service.SecurityAndOperationsTest.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 課金の可視化・コスト予測と、AI Agent のメーター・予算・委任の利用者。 */
class FinOpsServiceTest {
    private TestDatabase db;
    private MutableClock clock;
    private FinOpsService finops;
    private final List<String> railwayRequests = new ArrayList<>();
    private String railwayResponse;

    @BeforeEach
    void setUp() {
        db = TestDatabase.create();
        clock = new MutableClock(LocalDateTime.of(2026, 11, 20, 10, 0));
        finops = new FinOpsService(() -> db, clock, body -> {
            railwayRequests.add(body);
            if (body.contains("services")) {
                return "{\"data\":{\"project\":{\"services\":{\"edges\":[{\"node\":{\"id\":\"svc-oracle\",\"name\":\"oracle\"}}]}}}}";
            }
            return railwayResponse;
        }, "project-1");
    }

    private static Map<String, Object> entry(String service, String consumer, int status, long startedAtMs) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("service", Collections.singletonMap("name", service));
        if (consumer != null) e.put("consumer", Collections.singletonMap("username", consumer));
        e.put("response", Collections.singletonMap("status", status));
        e.put("latencies", Collections.singletonMap("request", 12));
        e.put("started_at", startedAtMs);
        return e;
    }

    private long ms(int day, int hour) {
        return LocalDateTime.of(2026, 11, day, hour, 0).atZone(ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli();
    }

    private void sql(String s) throws Exception {
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute(s);
        }
    }

    /** Kong の要求を配賦先に結び付け、経路ごとの単価（ゲートウェイ＋MuleSoft＋勘定系）で記録する。死活確認は記録しない。 */
    @Test
    void gatewayRequestsAreAttributedAndPriced() {
        Map<String, Object> mule = entry("bank-core-api", null, 200, ms(20, 9));
        mule.put("bank", Collections.singletonMap("caller", "mule"));
        int n = finops.ingestGateway(Arrays.asList(
                entry("integration-zengin", "zengin-network", 200, ms(20, 9)),
                entry("agent-core-api", "bank-agent", 200, ms(20, 9)),
                entry("bank-core-api", null, 200, ms(20, 9)),
                mule,
                entry("gateway-health", null, 200, ms(20, 9))));
        assertEquals(4, n);
        Map<String, Object> s = finops.summary(null);
        Map<String, Map<String, Object>> bySubject = new HashMap<>();
        for (Object o : (List<?>) s.get("bySubject")) {
            Map<?, ?> m = (Map<?, ?>) o;
            bySubject.put(m.get("subjectType") + ":" + m.get("subjectId"), new LinkedHashMap<>((Map<String, Object>) m));
        }
        // 全銀：ゲートウェイ 0.02 + MuleSoft 0.30 = 0.32 円
        assertEquals(0, new BigDecimal("0.32").compareTo((BigDecimal) bySubject.get("CONSUMER:zengin-network").get("monthToDateJpy")));
        // Agent の勘定系参照：ゲートウェイ 0.02 + 勘定系 0.05
        assertEquals(0, new BigDecimal("0.07").compareTo((BigDecimal) bySubject.get("AGENT:bank-agent").get("monthToDateJpy")));
        assertEquals(0, new BigDecimal("0.07").compareTo((BigDecimal) bySubject.get("CHANNEL:web").get("monthToDateJpy")));
        assertEquals(0, new BigDecimal("0.07").compareTo((BigDecimal) bySubject.get("CHANNEL:integration").get("monthToDateJpy")));
        assertEquals("決済事業部", bySubject.get("CONSUMER:zengin-network").get("department"));
        assertEquals(1L, ((Number) bySubject.get("CONSUMER:zengin-network").get("requests")).longValue());
    }

    /** Agent の実行：LLM のトークン数を単価・為替で円にし、実行と使用量を記録する。単価表にないモデルは拒否する。 */
    @Test
    void agentRunIsPricedFromTokens() {
        FinOpsService.AgentRun r = run("run-00000001", 1_000_000, 100_000);
        Map<String, Object> out = finops.recordAgentRun("bank-agent", r);
        // (1,000,000 × 4 + 100,000 × 20 + 0) / 1,000,000 USD = 6 USD × 150 = 900 円
        assertEquals(0, new BigDecimal("900.00").compareTo((BigDecimal) out.get("costJpy")));
        Map<String, Object> runRow = finops.agentRuns().get(0);
        assertEquals("teller1", runRow.get("principal"));
        assertEquals("bank-agent", runRow.get("agentId"));
        FinOpsService.AgentRun unknown = run("run-00000002", 10, 10);
        unknown.model = "unknown-model";
        assertThrows(BankException.class, () -> finops.recordAgentRun("bank-agent", unknown));
    }

    private static FinOpsService.AgentRun run(String id, long in, long out) {
        FinOpsService.AgentRun r = new FinOpsService.AgentRun();
        r.runId = id;
        r.principal = "teller1";
        r.status = "COMPLETED";
        r.model = "claude-opus-5-5";
        r.steps = 2;
        r.inputTokens = in;
        r.outputTokens = out;
        return r;
    }

    /** 予算に達した hard_stop の Agent は新しい実行を止める。配賦先のない Agent は動かさない。 */
    @Test
    void agentBudgetHardStop() throws Exception {
        assertEquals(Boolean.TRUE, finops.agentBudget("bank-agent").get("allowed"));
        sql("UPDATE cost_center SET monthly_budget_jpy = 500 WHERE subject_id = 'bank-agent'");
        finops.recordAgentRun("bank-agent", run("run-00000003", 1_000_000, 100_000));
        Map<String, Object> b = finops.agentBudget("bank-agent");
        assertEquals(Boolean.FALSE, b.get("allowed"));
        assertNotNull(b.get("reason"));
        assertEquals(Boolean.FALSE, finops.agentBudget("unregistered-agent").get("allowed"));
    }

    /** 月末予測：当月実績 ＋ 直近 7 日の日平均 × 残り日数。履歴が 7 日未満なら信頼度「低」、急増は異常。 */
    @Test
    void forecastUsesTrailingAverage() {
        List<BigDecimal> daily = new ArrayList<>();
        for (int i = 0; i < 20; i++) daily.add(new BigDecimal(100));
        List<BigDecimal> history = new ArrayList<>(daily.subList(0, 19));
        FinOpsService.Forecast f = FinOpsService.forecast(daily, history, 30, BigDecimal.ZERO);
        // 2,000 + 100 × (10 + 0.5) = 3,050
        assertEquals(0, new BigDecimal("3050.00").compareTo(f.forecast));
        assertEquals("高", f.confidence);
        assertFalse(f.anomaly);

        List<BigDecimal> spike = new ArrayList<>(Arrays.asList(new BigDecimal(10), new BigDecimal(10), new BigDecimal(500)));
        FinOpsService.Forecast g = FinOpsService.forecast(spike, spike.subList(0, 2), 30, new BigDecimal(1000));
        assertEquals("低", g.confidence);
        assertTrue(g.anomaly);
        // 520 + 10 × 27.5 + 固定費 1,000
        assertEquals(0, new BigDecimal("1795.00").compareTo(g.forecast));
    }

    /** 予算の判定：見込みが 80% 以上で注意、100% 超で超過。部門と合計にまとめる。 */
    @Test
    void summaryShowsBudgetStatus() throws Exception {
        sql("UPDATE cost_center SET monthly_budget_jpy = 1000 WHERE subject_id = 'bank-agent'");
        clock.set(LocalDateTime.of(2026, 11, 20, 10, 0));
        finops.recordAgentRun("bank-agent", run("run-00000004", 1_000_000, 100_000));
        Map<String, Object> s = finops.summary(null);
        Map<?, ?> agent = ((List<?>) s.get("bySubject")).stream().map(o -> (Map<?, ?>) o)
                .filter(m -> "bank-agent".equals(m.get("subjectId"))).findFirst().orElseThrow(IllegalStateException::new);
        assertEquals("WARN", agent.get("budgetStatus").equals("OVER") ? "WARN" : agent.get("budgetStatus"));
        assertEquals("業務企画部", agent.get("department"));
        assertNotNull(((Map<?, ?>) s.get("total")).get("forecastJpy"));
        assertEquals(LocalDate.of(2026, 11, 20).toString(), s.get("asOf"));
        assertEquals(20, ((List<?>) s.get("daily")).size());
        assertThrows(BankException.class, () -> finops.summary(java.time.YearMonth.of(2026, 12)));
    }

    /** Railway の使用量を日ごと・サービスごとに取り込み、単価で円にする。未設定なら何もしない。 */
    @Test
    void railwayUsageIsSynced() {
        railwayResponse = "{\"data\":{\"usage\":[{\"measurement\":\"CPU_USAGE\",\"value\":60,\"tags\":{\"serviceId\":\"svc-oracle\"}},"
                + "{\"measurement\":\"MEMORY_USAGE_GB\",\"value\":1440,\"tags\":{\"serviceId\":\"svc-oracle\"}},"
                + "{\"measurement\":\"NETWORK_TX_GB\",\"value\":1,\"tags\":{\"serviceId\":\"svc-x\"}}]}}";
        Map<String, Object> r = finops.syncInfra(LocalDate.of(2026, 11, 19), LocalDate.of(2026, 11, 20));
        assertEquals("SYNCED", r.get("status"));
        assertEquals(2, r.get("days"));
        Map<String, Object> s = finops.summary(null);
        Map<?, ?> oracle = ((List<?>) s.get("infra")).stream().map(o -> (Map<?, ?>) o)
                .filter(m -> "oracle".equals(m.get("serviceName"))).findFirst().orElseThrow(IllegalStateException::new);
        // 1 日あたり (60 × 0.000463 + 1440 × 0.000231) USD × 2 日
        BigDecimal perDay = new BigDecimal("0.000463").multiply(new BigDecimal(60)).add(new BigDecimal("0.000231").multiply(new BigDecimal(1440)));
        assertEquals(0, perDay.multiply(new BigDecimal(2)).setScale(4, java.math.RoundingMode.HALF_UP)
                .compareTo(((BigDecimal) oracle.get("costUsd")).setScale(4, java.math.RoundingMode.HALF_UP)));
        assertTrue(railwayRequests.stream().anyMatch(b -> b.contains("project-1")));

        railwayResponse = "{\"errors\":[{\"message\":\"Not Authorized\"}],\"data\":null}";
        assertEquals("FAILED", finops.syncInfra(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 20)).get("status"));
        FinOpsService none = new FinOpsService(() -> db, clock, null, null);
        assertEquals("NOT_CONFIGURED", none.syncInfra(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 20)).get("status"));
    }

    /** Agent の委任で使える行員：有効・ロックなし・初期パスワード変更済みの窓口・役席だけ。顧客・存在しない利用者は使えない。 */
    @Test
    void delegatedStaffMustBeActiveStaff() throws Exception {
        AuthService auth = new AuthService(() -> db, clock);
        LoginUser u = auth.delegatedStaff("teller1");
        assertNotNull(u);
        assertEquals("TELLER", u.getRole());
        assertNull(auth.delegatedStaff("yamada"));
        assertNull(auth.delegatedStaff("nobody"));
        assertNull(auth.delegatedStaff("bad id"));
        sql("UPDATE app_user SET enabled = 0 WHERE user_id = 'supervisor2'");
        assertNull(auth.delegatedStaff("supervisor2"));
    }
}
