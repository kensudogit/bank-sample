package com.example.bank.service;

import com.example.bank.domain.Account;
import com.example.bank.domain.AuditEntry;
import com.example.bank.domain.BankException;
import com.example.bank.domain.Customer;
import com.example.bank.domain.ErrorCode;
import com.example.bank.domain.LoginUser;
import com.example.bank.domain.NewCustomer;
import com.example.bank.support.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 認証・認可の前提となるサービス、監査ログ、日次上限、口座検索、夜間バッチのテスト。 */
class SecurityAndOperationsTest {
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    // db/init/04_security.sql のデモ利用者と同じパスワード（ハッシュは Java の外で生成したもの）
    /** Same password as the demo users in db/init/04_security.sql (hashes generated outside Java) */
    private static final String DEMO_PASSWORD = "BankDemo#2026";

    // テストから時刻を進められる時計（ロック期限や夜間バッチの日付を再現する）
    /** Clock that tests can move. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(LocalDateTime local) { set(local); }

        void set(LocalDateTime local) { now = local.atZone(JST).toInstant(); }

        @Override public ZoneId getZone() { return JST; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private TestDatabase db;
    private MutableClock clock;
    private AuthService auth;
    private AuditService audit;
    private BankService bank;
    private CustomerService customers;

    /** テストごとに新しいインメモリ DB を作り、時計を 2026-11-20 10:00 に設定する。 */
    @BeforeEach
    void setUp() {
        db = TestDatabase.create();
        clock = new MutableClock(LocalDateTime.of(2026, 11, 20, 10, 0));
        auth = new AuthService(() -> db, clock);
        audit = new AuditService(() -> db, clock);
        bank = new BankService(() -> db, clock);
        customers = new CustomerService(() -> db, clock);
    }

    private static String key() { return UUID.randomUUID().toString(); }

    // 業務例外が期待どおりのエラーコードで発生することを確かめる
    private static void assertError(ErrorCode expected, Executable call) {
        BankException e = assertThrows(BankException.class, call);
        assertEquals(expected, e.getCode());
    }

    // テストの前提を作るため、DB を直接書き換える
    private void sql(String statement) throws Exception {
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute(statement);
        }
    }

    // ---- passwords / login ----

    /** パスワードのハッシュ化（ソルト付き・照合・ポリシー）。 */
    @Test
    void passwordHashing() {
        String h = PasswordHasher.hash("Abcdefgh12");
        assertTrue(h.startsWith("pbkdf2_sha256$120000$"));
        assertTrue(PasswordHasher.verify("Abcdefgh12", h));
        assertFalse(PasswordHasher.verify("Abcdefgh13", h));
        assertNotEquals(h, PasswordHasher.hash("Abcdefgh12"));   // random salt
        assertFalse(PasswordHasher.verify("x", null));
        assertTrue(PasswordHasher.meetsPolicy("abcdefgh12"));
        assertFalse(PasswordHasher.meetsPolicy("abcdefghij"));   // no digit
        assertFalse(PasswordHasher.meetsPolicy("abc12"));        // too short
        assertFalse(PasswordHasher.meetsPolicy("abcdefgh 12"));  // space
        assertTrue(PasswordHasher.meetsPolicy(PasswordHasher.randomPassword(12)));
    }

    /** 初期データのデモ利用者でログインできる。 */
    @Test
    void demoUsersFromSeedCanLogIn() {
        for (String userId : Arrays.asList("yamada", "sato", "suzuki", "takahashi", "teller1", "supervisor1", "supervisor2")) {
            LoginUser u = auth.login(userId, DEMO_PASSWORD);
            assertEquals(userId, u.getUserId());
            assertFalse(u.isMustChangePassword());
        }
        LoginUser yamada = auth.login("yamada", DEMO_PASSWORD);
        assertTrue(yamada.isCustomer());
        assertEquals("CU0001", yamada.getCustomerId());
        LoginUser teller = auth.login("teller1", DEMO_PASSWORD);
        assertTrue(teller.isStaff());
        assertNull(teller.getCustomerId());
    }

    /** 連続して誤ったパスワードを入れると一定時間ロックされる。 */
    @Test
    void wrongPasswordsLockTheAccountTemporarily() {
        assertError(ErrorCode.AUTH_FAILED, () -> auth.login("nobody", DEMO_PASSWORD));
        for (int i = 1; i < AuthService.MAX_FAILURES; i++) {
            assertError(ErrorCode.AUTH_FAILED, () -> auth.login("sato", "wrong-password-1"));
        }
        assertError(ErrorCode.ACCOUNT_LOCKED, () -> auth.login("sato", "wrong-password-1"));   // 5th failure
        assertError(ErrorCode.ACCOUNT_LOCKED, () -> auth.login("sato", DEMO_PASSWORD));        // locked even if right

        clock.set(LocalDateTime.of(2026, 11, 20, 10, AuthService.LOCK_MINUTES + 1));
        assertEquals("sato", auth.login("sato", DEMO_PASSWORD).getUserId());
        // ログインに成功すると失敗回数がリセットされる
        // A success resets the counter
        assertError(ErrorCode.AUTH_FAILED, () -> auth.login("sato", "wrong-password-1"));
        assertEquals("sato", auth.login("sato", DEMO_PASSWORD).getUserId());
    }

    /** パスワード変更（現在のパスワードの確認・ポリシー違反の拒否）。 */
    @Test
    void passwordChange() {
        LoginUser u = auth.login("suzuki", DEMO_PASSWORD);
        assertError(ErrorCode.PASSWORD_POLICY, () -> auth.changePassword(u, DEMO_PASSWORD, "short1"));
        assertError(ErrorCode.PASSWORD_POLICY, () -> auth.changePassword(u, DEMO_PASSWORD, DEMO_PASSWORD));
        assertError(ErrorCode.AUTH_FAILED, () -> auth.changePassword(u, "not-the-password1", "NewPassword2026"));
        auth.changePassword(u, DEMO_PASSWORD, "NewPassword2026");
        assertError(ErrorCode.AUTH_FAILED, () -> auth.login("suzuki", DEMO_PASSWORD));
        assertEquals("suzuki", auth.login("suzuki", "NewPassword2026").getUserId());
    }

    /** 顧客ログインの発行は本人確認済みが条件で、初回ログイン時にパスワード変更を求める。 */
    @Test
    void customerLoginIssueRequiresKycAndForcesPasswordChange() {
        Customer c = customers.register(new NewCustomer("伊藤 健", "イトウ ケン", LocalDate.of(1990, 4, 1), "060-0001",
                "北海道札幌市", "0111234567", "PASSPORT", "TK1234567"), key(), "teller1");
        assertError(ErrorCode.KYC_NOT_VERIFIED, () -> auth.issueCustomerLogin(c.getCustomerId()));
        customers.decideKyc(c.getCustomerId(), "VERIFIED", "確認", "supervisor1");

        Map<String, String> issued = auth.issueCustomerLogin(c.getCustomerId());
        assertEquals("cu0005", issued.get("userId"));
        LoginUser first = auth.login("cu0005", issued.get("initialPassword"));
        assertTrue(first.isMustChangePassword());
        assertEquals(c.getCustomerId(), first.getCustomerId());
        LoginUser changed = auth.changePassword(first, issued.get("initialPassword"), "MyOwnPass2026");
        assertFalse(changed.isMustChangePassword());
        assertFalse(auth.login("cu0005", "MyOwnPass2026").isMustChangePassword());

        assertError(ErrorCode.LOGIN_ALREADY_ISSUED, () -> auth.issueCustomerLogin(c.getCustomerId()));
        assertError(ErrorCode.LOGIN_ALREADY_ISSUED, () -> auth.issueCustomerLogin("CU0001"));   // seed user exists
    }

    /** 本人確認の判定は、申込を受け付けた行員とは別の人でなければならない（職務分離）。 */
    @Test
    void kycDecisionNeedsADifferentPersonThanTheRegistrant() {
        Customer c = customers.register(new NewCustomer("伊藤 健", "イトウ ケン", LocalDate.of(1990, 4, 1), "060-0001",
                "北海道札幌市", "0111234567", "PASSPORT", "TK1234567"), key(), "supervisor1");
        assertEquals("supervisor1", c.getRegisteredBy());
        assertError(ErrorCode.SEGREGATION_OF_DUTIES,
                () -> customers.decideKyc(c.getCustomerId(), "VERIFIED", "確認", "supervisor1"));
        Customer decided = customers.decideKyc(c.getCustomerId(), "VERIFIED", "確認", "supervisor2");
        assertEquals("supervisor2", decided.getKycDecidedBy());
    }

    // ---- audit log ----

    /** 監査ログのハッシュチェーンで改ざん・途中の削除・末尾の削除を検出する。 */
    @Test
    void auditChainDetectsTamperingAndDeletion() throws Exception {
        LoginUser teller = auth.login("teller1", DEMO_PASSWORD);
        audit.record(teller, null, "deposit", "1000001 100", AuditService.SUCCESS, null, "10.0.0.1");
        audit.record(null, "unknown", "login", null, AuditService.FAILURE, "AUTH_FAILED", "10.0.0.2");
        audit.record(teller, null, "customers", null, AuditService.DENIED, "FORBIDDEN", "10.0.0.1");

        Map<String, Object> ok = audit.verify();
        assertEquals(true, ok.get("valid"));
        assertEquals(3L, ok.get("checked"));

        @SuppressWarnings("unchecked")
        List<AuditEntry> failures = (List<AuditEntry>) audit.search(null, null, null, null, "FAILURE", 1, 50).get("items");
        assertEquals(1, failures.size());
        assertEquals("unknown", failures.get(0).getUserId());
        assertEquals(2L, audit.search(null, null, "teller1", null, null, 1, 50).get("total"));

        sql("UPDATE audit_log SET detail = 'edited' WHERE audit_id = 2");
        Map<String, Object> tampered = audit.verify();
        assertEquals(false, tampered.get("valid"));
        assertEquals(2L, tampered.get("brokenAt"));
        sql("UPDATE audit_log SET detail = 'AUTH_FAILED' WHERE audit_id = 2");
        assertEquals(true, audit.verify().get("valid"));

        sql("DELETE FROM audit_log WHERE audit_id = 3");
        Map<String, Object> truncated = audit.verify();
        assertEquals(false, truncated.get("valid"));
        assertEquals(3L, truncated.get("brokenAt"));
    }

    // ---- limits / search ----

    /** 1 口座 1 日あたりの出金合計の上限。 */
    @Test
    void dailyOutflowLimitPerAccount() {
        for (int i = 0; i < 3; i++) bank.deposit("1000004", new BigDecimal("10000000"), null, key());
        bank.withdraw("1000004", new BigDecimal("10000000"), null, key());
        bank.transfer("1000004", "1000001", new BigDecimal("9999999"), null, key());
        assertError(ErrorCode.DAILY_LIMIT_EXCEEDED, () -> bank.withdraw("1000004", new BigDecimal("2"), null, key()));
        bank.withdraw("1000004", BigDecimal.ONE, null, key());   // exactly 20,000,000 today
        assertError(ErrorCode.DAILY_LIMIT_EXCEEDED,
                () -> bank.transfer("1000004", "1000001", BigDecimal.ONE, null, key()));
        // 入金は上限の対象外。翌日になると合計は 0 から数え直す
        // Deposits are not limited; the next day starts a new total
        bank.deposit("1000004", BigDecimal.ONE, null, key());
        clock.set(LocalDateTime.of(2026, 11, 21, 9, 0));
        bank.withdraw("1000004", new BigDecimal("10000000"), null, key());
    }

    /** 口座検索は顧客なら本人の口座に限られ、ページングされる。 */
    @Test
    void accountSearchIsScopedAndPaged() {
        Map<String, Object> own = bank.searchAccounts("CU0001", null, 1, 50);
        assertEquals(2L, own.get("total"));
        assertEquals(0, new BigDecimal("4250000").compareTo((BigDecimal) own.get("totalBalance")));

        Map<String, Object> page1 = bank.searchAccounts(null, null, 1, 3);
        assertEquals(8L, page1.get("total"));
        assertEquals(true, page1.get("hasNext"));
        assertEquals(3, ((List<?>) page1.get("items")).size());
        Map<String, Object> page3 = bank.searchAccounts(null, null, 3, 3);
        assertEquals(false, page3.get("hasNext"));
        assertEquals(2, ((List<?>) page3.get("items")).size());

        assertEquals(2L, bank.searchAccounts(null, "サトウ", 1, 50).get("total"));     // kana
        assertEquals(2L, bank.searchAccounts(null, "佐藤", 1, 50).get("total"));       // name
        assertEquals(2L, bank.searchAccounts(null, "cu0003", 1, 50).get("total"));     // customer id
        assertEquals(1L, bank.searchAccounts(null, "1000007", 1, 50).get("total"));    // account number
        assertEquals(0L, bank.searchAccounts(null, "%", 1, 50).get("total"));          // wildcard is literal
        assertEquals(0L, bank.searchAccounts("CU0001", "佐藤", 1, 50).get("total"));    // scope wins
        @SuppressWarnings("unchecked")
        List<Account> items = (List<Account>) bank.searchAccounts(null, "1000", 1, 50).get("items");
        assertEquals("1000001", items.get(0).getAccountNo());
    }

    // ---- nightly batch ----

    /** 夜間バッチは同じ業務日に 1 回だけ実行される（2 回目の呼び出しでは何もしない）。 */
    @Test
    void nightlyJobsRunOncePerBusinessDay() {
        clock.set(LocalDateTime.of(2026, 3, 1, 0, 30));
        JobRunner jobs = new JobRunner(() -> db, clock);
        assertTrue(jobs.runDue().isEmpty());   // before 01:00

        clock.set(LocalDateTime.of(2026, 3, 1, 1, 5));
        assertEquals(Arrays.asList(JobRunner.DORMANCY, JobRunner.TIME_MATURITY, JobRunner.LOAN_DUE, JobRunner.DIRECT_DEBIT,
                JobRunner.INTEREST, JobRunner.LEDGER_CHECK, JobRunner.REQUEST_CLEANUP), jobs.runDue());
        assertTrue(jobs.runDue().isEmpty());                                   // same day: already done
        assertTrue(new JobRunner(() -> db, clock).runDue().isEmpty());         // another instance too

        // 利息は 2 月末まで決算された
        // Interest was settled through the end of February
        assertEquals("INTEREST", bank.getRecentTransactions("1000004").get(1).getTxType());
        // 2026-03-01 時点の休眠処理: 1000006（最終異動 2015-06-01）が休眠になった
        // Dormancy as of 2026-03-01: 1000006 (last activity 2015-06-01) became dormant
        assertEquals("DORMANT", bank.getAccount("1000006").getStatus());

        clock.set(LocalDateTime.of(2026, 3, 2, 1, 5));
        assertEquals(Arrays.asList(JobRunner.DORMANCY, JobRunner.TIME_MATURITY, JobRunner.LOAN_DUE, JobRunner.DIRECT_DEBIT,
                JobRunner.LEDGER_CHECK, JobRunner.REQUEST_CLEANUP), jobs.runDue());   // no interest

        List<Map<String, Object>> runs = jobs.recentRuns(20);
        assertEquals(13, runs.size());
        for (Map<String, Object> r : runs) assertEquals("SUCCESS", r.get("outcome"));
        assertEquals(13L, audit.search(null, null, "SYSTEM", null, null, 1, 50).get("total"));
        assertEquals(true, audit.verify().get("valid"));
    }

    /** 冪等キーの削除は保存期間を過ぎたものだけを対象にする。 */
    @Test
    void requestCleanupRemovesOnlyOldKeys() throws Exception {
        String oldKey = key();
        bank.deposit("1000001", BigDecimal.ONE, null, oldKey);
        sql("UPDATE operation_request SET created_at = TIMESTAMP '2026-01-01 00:00:00' WHERE idempotency_key = '"
                + oldKey + "'");
        String recentKey = key();
        bank.deposit("1000001", BigDecimal.ONE, null, recentKey);

        clock.set(LocalDateTime.of(2026, 11, 21, 2, 0));
        new JobRunner(() -> db, clock).runDue();
        assertError(ErrorCode.DUPLICATE_REQUEST, () -> bank.deposit("1000001", BigDecimal.ONE, null, recentKey));
        bank.deposit("1000001", BigDecimal.ONE, null, oldKey);   // old key was removed (> 90 days)
    }
}
