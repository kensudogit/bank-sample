package com.example.bank.service;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.domain.LoginUser;
import com.example.bank.service.SecurityAndOperationsTest.MutableClock;
import com.example.bank.support.Transactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** ACID・排他制御・二重処理防止・障害時の再試行・元帳の改ざん検知と追跡。 */
class ReliabilityTest {
    private TestDatabase db;
    private MutableClock clock;
    private BankService bank;
    private LedgerService ledger;
    private PaymentService payments;

    /** テストごとに新しいインメモリ DB を作り、日時を固定したサービスを用意する。 */
    @BeforeEach
    void setUp() {
        db = TestDatabase.create();
        clock = new MutableClock(LocalDateTime.of(2026, 11, 20, 10, 0));
        bank = new BankService(() -> db, clock);
        ledger = new LedgerService(() -> db, clock);
        payments = new PaymentService(() -> db, clock);
    }

    private static String key() { return UUID.randomUUID().toString(); }

    private static BigDecimal yen(long v) { return BigDecimal.valueOf(v); }

    private BigDecimal balance(String no) { return bank.getAccount(no).getBalance(); }

    // 改ざんや障害を再現するため、アプリケーションを通さずに DB を直接書き換える
    private void sql(String statement) throws Exception {
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute(statement);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> problems(Map<String, Object> r) {
        return (List<Map<String, Object>>) r.get("problems");
    }

    // ---------------- ACID: retry of transient failures ----------------

    /** 最初の n 回だけ接続に失敗する DataSource（DB の一時的な障害・フェイルオーバーの模擬）。 */
    static final class FlakyDataSource implements DataSource {
        private final DataSource target;
        private final AtomicInteger failures;

        // @param target 本来の DataSource / @param failures 失敗させる回数
        FlakyDataSource(DataSource target, int failures) {
            this.target = target;
            this.failures = new AtomicInteger(failures);
        }

        @Override public Connection getConnection() throws SQLException {
            // SQLState 08（接続エラー）を返し、Transactions が「再試行してよい一時的な障害」と判定することを確かめる
            if (failures.getAndDecrement() > 0) throw new SQLTransientConnectionException("simulated failover", "08006");
            return target.getConnection();
        }
        @Override public Connection getConnection(String u, String p) throws SQLException { return getConnection(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    /** 一時的な障害はコミット前なら自動で再試行され、処理は 1 回だけ反映される。 */
    @Test
    void transientFailuresAreRetriedAndAppliedOnce() {
        BankService flaky = new BankService(() -> new FlakyDataSource(db, 2), clock);
        // 2 回失敗し、3 回目で成功する
        flaky.deposit("1000001", yen(1000), null, key());   // fails twice, succeeds on the 3rd attempt
        assertEquals(0, yen(1_251_000).compareTo(balance("1000001")));

        BankService broken = new BankService(() -> new FlakyDataSource(db, 5), clock);
        assertThrows(RuntimeException.class, () -> broken.deposit("1000001", yen(1000), null, key()));
        assertEquals(0, yen(1_251_000).compareTo(balance("1000001")));   // nothing applied
    }

    /** 再試行してよい障害（接続断・デッドロック・直列化失敗）とそれ以外の分類。 */
    @Test
    void transientErrorClassification() {
        assertTrue(Transactions.isTransient(new SQLTransientConnectionException("x", "08006")));
        assertTrue(Transactions.isTransient(new SQLException("deadlock", "61000", 60)));
        assertTrue(Transactions.isTransient(new SQLException("serialization", "40001")));
        assertFalse(Transactions.isTransient(new SQLIntegrityConstraintViolationException("dup", "23000", 1)));
        assertFalse(Transactions.isTransient(new SQLException("syntax", "42000", 900)));
    }

    // ---------------- isolation / locking ----------------

    /** 同時出金でも行ロックにより残高がマイナスにならない（更新の消失が起きない）。 */
    @Test
    void concurrentWithdrawalsNeverOverdraw() throws Exception {
        // 1000004 の残高は 120,000 円: 100,000 円の出金を 20 件同時に行っても成功するのは 1 件だけ
        // 1000004 has 120,000: of 20 concurrent withdrawals of 100,000 exactly one may succeed
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 20; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        bank.withdraw("1000004", yen(100_000), null, key());
                        return true;
                    } catch (BankException e) {
                        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, e.getCode());
                        return false;
                    }
                }));
            }
            start.countDown();
            int ok = 0;
            for (Future<Boolean> f : results) if (f.get()) ok++;
            assertEquals(1, ok);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, yen(20_000).compareTo(balance("1000004")));
        assertEquals(true, ledger.verify("1000004").get("valid"));
    }

    /** 同じ冪等キーの要求が同時に届いても、主キー制約により 1 回だけ実行される。 */
    @Test
    void sameKeySentConcurrentlyIsExecutedOnce() throws Exception {
        String k = key();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        // 全スレッドを待たせてから一斉に開始し、同時実行の状況を作る
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 10; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        bank.transfer("1000001", "1000003", yen(5_000), "同時再送", k);
                        return "OK";
                    } catch (BankException e) {
                        return e.getCode().name();
                    }
                }));
            }
            start.countDown();
            int ok = 0;
            for (Future<String> f : results) {
                String r = f.get();
                if ("OK".equals(r)) ok++;
                else assertEquals("DUPLICATE_REQUEST", r);
            }
            assertEquals(1, ok);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, yen(1_245_000).compareTo(balance("1000001")));
        assertEquals(0, yen(485_000).compareTo(balance("1000003")));
    }

    // ---------------- idempotent replay storage ----------------

    /** 成功した結果が冪等キーに保存され、再送時に同じ結果を返せる。 */
    @Test
    void idempotentResultIsStoredForReplay() {
        IdempotencyService idem = new IdempotencyService(() -> db, clock);
        String k = key();
        bank.deposit("1000001", yen(10), null, k);
        assertNull(idem.storedResult(k));                        // committed but result not stored yet: "processing"
        idem.saveResult(k, "deposit|1000001 10", "{\"amount\":10}");
        assertArrayEquals(new String[]{"deposit|1000001 10", "{\"amount\":10}"}, idem.storedResult(k));
        idem.saveResult(k, "other", "{}");                       // first result wins
        assertEquals("deposit|1000001 10", idem.storedResult(k)[0]);
        assertNull(idem.storedResult("no-such-key-000000"));
    }

    // ---------------- ledger integrity / audit trail ----------------

    /** 元帳検証で明細の改ざん・残高の直接変更・振込の片側削除を検出する。 */
    @Test
    void ledgerVerificationDetectsTamperingAndInconsistency() throws Exception {
        bank.deposit("1000001", yen(1000), "a", key());
        bank.transfer("1000001", "1000003", yen(300), "b", key());
        Map<String, Object> ok = ledger.verify(null);
        assertEquals(true, ok.get("valid"), String.valueOf(ok.get("problems")));

        // 記録済みの金額を書き換えると、ハッシュチェーンと残高の連続性の両方が崩れる
        // Change a recorded amount: both the hash chain and the balance continuity break
        sql("UPDATE account_transaction SET amount = 999 WHERE account_no = '1000001' AND memo = 'a'");
        Map<String, Object> tampered = ledger.verify("1000001");
        assertEquals(false, tampered.get("valid"));
        assertTrue(problems(tampered).stream().anyMatch(p -> "ハッシュチェーン".equals(p.get("check"))));
        assertTrue(problems(tampered).stream().anyMatch(p -> "残高の連続性".equals(p.get("check"))));
        sql("UPDATE account_transaction SET amount = 1000 WHERE account_no = '1000001' AND memo = 'a'");
        assertEquals(true, ledger.verify("1000001").get("valid"));

        // 元帳を通さずに残高を直接書き換える
        // Change a balance directly (outside the ledger)
        sql("UPDATE account SET balance = balance + 1 WHERE account_no = '1000003'");
        assertTrue(problems(ledger.verify("1000003")).stream().anyMatch(p -> "残高照合".equals(p.get("check"))));
        sql("UPDATE account SET balance = balance - 1 WHERE account_no = '1000003'");

        // 振込の片側の明細を削除する
        // Delete one side of a transfer
        sql("DELETE FROM account_transaction WHERE account_no = '1000003' AND memo = 'b'");
        Map<String, Object> deleted = ledger.verify(null);
        assertTrue(problems(deleted).stream().anyMatch(p -> "振込の対応".equals(p.get("check"))));
    }

    /** 別口座への解約払戻し（CLOSE_PAYOUT と TRANSFER_IN）は対として整合する。 */
    @Test
    void closurePaidIntoAnotherAccountIsAConsistentPair() {
        AccountLifecycleService lifecycle = new AccountLifecycleService(() -> db, clock);
        lifecycle.close("1000001", "1000002", key());    // same holder: CLOSE_PAYOUT + TRANSFER_IN
        lifecycle.close("1000004", null, key());         // cash payout: no pair
        Map<String, Object> r = ledger.verify(null);
        assertEquals(true, r.get("valid"), String.valueOf(r.get("problems")));
    }

    /** 明細から、それを実行した操作（監査ログ）を冪等キーでたどれる。 */
    @Test
    void transactionsAreTracedToTheOperation() {
        String k = key();
        bank.deposit("1000004", yen(5000), "窓口入金", k);
        LoginUser teller = new LoginUser("teller1", "窓口 一郎", LoginUser.TELLER, null, false);
        new AuditService(() -> db, clock).record(teller, null, "deposit", "1000004 5000", AuditService.SUCCESS, null,
                "10.0.0.5", k);
        List<Map<String, Object>> trace = ledger.trace("1000004", 10);
        Map<String, Object> latest = trace.get(0);
        assertEquals("DEPOSIT", latest.get("txType"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ops = (List<Map<String, Object>>) latest.get("operations");
        assertEquals(1, ops.size());
        assertEquals("teller1", ops.get(0).get("userId"));
        assertEquals("10.0.0.5", ops.get(0).get("clientIp"));
        assertEquals(true, new AuditService(() -> db, clock).verify().get("valid"));
    }

    // ---------------- duplicate files from outside ----------------

    /** 同じ参照番号の被仕向は 1 回だけ入金される（ファイルの二重取込対策）。 */
    @Test
    void incomingCreditWithTheSameReferenceIsAppliedOnce() {
        PaymentService.Incoming in = new PaymentService.Incoming();
        in.bankCode = "0005";
        in.referenceNo = "ZG-20261120-0001";
        in.remitterNameKana = "ﾃｽﾄ";
        in.accountNo = "1000003";
        in.nameKana = "サトウ ハナコ";
        in.amount = yen(10_000);
        List<Map<String, Object>> r = payments.receiveIncoming(Arrays.asList(in, in), "supervisor1");
        assertEquals("CREDITED", r.get(0).get("status"));
        assertEquals("DUPLICATE", r.get(1).get("status"));
        assertEquals("DUPLICATE", payments.receiveIncoming(Arrays.asList(in), "supervisor1").get(0).get("status"));
        assertEquals(0, yen(490_000).compareTo(balance("1000003")));
    }

    /** 同じ請求データを 2 回取り込んでも 1 回だけ引き落とされる。 */
    @Test
    void billingImportedTwiceIsDebitedOnce() {
        payments.registerMandate("1000004", "ELEC01", "E-7777", "teller1");
        PaymentService.Billing b = new PaymentService.Billing();
        b.billerCode = "ELEC01";
        b.payerNumber = "E-7777";
        b.amount = yen(5000);
        b.dueDate = LocalDate.of(2026, 11, 20);
        payments.importBilling(Arrays.asList(b));
        assertEquals("重複（取込済み）", payments.importBilling(Arrays.asList(b)).get(0).get("result"));
        payments.processDirectDebits(LocalDate.of(2026, 11, 20));
        assertEquals(0, yen(115_000).compareTo(balance("1000004")));
    }
}
