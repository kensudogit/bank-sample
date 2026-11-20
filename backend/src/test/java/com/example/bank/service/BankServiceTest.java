package com.example.bank.service;

import com.example.bank.domain.Account;
import com.example.bank.domain.AccountTransaction;
import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.domain.OperationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** 照会・入金・出金・振込の基本動作と、ロールバック・冪等性・排他制御のテスト（H2 / Oracle 互換モード）。 */
class BankServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-01T01:00:00Z"), ZoneId.of("Asia/Tokyo"));

    private TestDatabase db;
    private BankService service;

    /** テストごとに新しいインメモリ DB を作る（テスト間で残高を共有しない）。 */
    @BeforeEach
    void setUp() {
        db = TestDatabase.create();
        service = new BankService(() -> db, CLOCK);
    }

    private static String key() { return UUID.randomUUID().toString(); }

    private BigDecimal balance(String accountNo) { return service.getAccount(accountNo).getBalance(); }

    // 業務例外が期待どおりのエラーコードで発生することを確かめる
    private static void assertError(ErrorCode expected, Executable call) {
        BankException e = assertThrows(BankException.class, call);
        assertEquals(expected, e.getCode());
    }

    /** 初期データの口座が名義人名付きで一覧できる。 */
    @Test
    void listsSeededAccountsWithCustomerNames() {
        List<Account> accounts = service.listAccounts();
        assertEquals(8, accounts.size());   // incl. the corporate checking account
        assertEquals("1000001", accounts.get(0).getAccountNo());
        assertEquals("山田 太郎", accounts.get(0).getCustomerName());
        assertEquals(0, new BigDecimal("1250000").compareTo(accounts.get(0).getBalance()));
    }

    /** 入金で残高が増え、明細が記録される。 */
    @Test
    void depositIncreasesBalanceAndRecordsHistory() {
        OperationResult r = service.deposit("1000003", new BigDecimal("20000"), "給与", key());
        assertEquals(0, new BigDecimal("500000").compareTo(r.getBalanceAfter()));
        assertEquals("2026-03-01T10:00:00", r.getExecutedAt());
        assertEquals(0, new BigDecimal("500000").compareTo(balance("1000003")));

        AccountTransaction latest = service.getRecentTransactions("1000003").get(0);
        assertEquals("DEPOSIT", latest.getTxType());
        assertEquals("給与", latest.getMemo());
    }

    /** 残高不足の出金は拒否され、冪等キーの登録も含めて全体がロールバックされる。 */
    @Test
    void withdrawWithInsufficientFundsIsRejectedAndRolledBack() {
        String k = key();
        assertError(ErrorCode.INSUFFICIENT_FUNDS,
                () -> service.withdraw("1000004", new BigDecimal("120001"), null, k));
        assertEquals(0, new BigDecimal("120000").compareTo(balance("1000004")));
        // 冪等キーの登録もロールバックされたので、修正した要求で同じキーを使える
        // The idempotency key was rolled back too, so the corrected request can reuse it
        service.withdraw("1000004", new BigDecimal("120000"), null, k);
        assertEquals(0, BigDecimal.ZERO.compareTo(balance("1000004")));
    }

    /** 振込は 2 口座の残高を同時に更新し、出金側・入金側の両方の明細を記録する。 */
    @Test
    void transferMovesMoneyAtomicallyAndRecordsBothSides() {
        OperationResult r = service.transfer("1000001", "1000003", new BigDecimal("250000"), "家賃", key());
        assertEquals(0, new BigDecimal("1000000").compareTo(r.getBalanceAfter()));
        assertEquals(0, new BigDecimal("1000000").compareTo(balance("1000001")));
        assertEquals(0, new BigDecimal("730000").compareTo(balance("1000003")));

        AccountTransaction out = service.getRecentTransactions("1000001").get(0);
        AccountTransaction in = service.getRecentTransactions("1000003").get(0);
        assertEquals("TRANSFER_OUT", out.getTxType());
        assertEquals("1000003", out.getCounterpartyAccountNo());
        assertEquals("TRANSFER_IN", in.getTxType());
        assertEquals("1000001", in.getCounterpartyAccountNo());
    }

    /** 口座詳細は口座と最新の明細を 1 回の呼び出しで返す。 */
    @Test
    void accountDetailReturnsAccountAndNewestTransactionsInOneCall() {
        service.deposit("1000004", new BigDecimal("1000"), "1回目", key());
        service.deposit("1000004", new BigDecimal("2000"), "2回目", key());

        com.example.bank.domain.AccountDetail detail = service.getAccountDetail("1000004");
        assertEquals("1000004", detail.getAccount().getAccountNo());
        assertEquals("鈴木 一郎", detail.getAccount().getCustomerName());
        assertEquals(0, new BigDecimal("123000").compareTo(detail.getAccount().getBalance()));
        assertEquals("2回目", detail.getTransactions().get(0).getMemo());
        assertTrue(detail.getTransactions().size() <= BankService.HISTORY_LIMIT);
        assertError(ErrorCode.ACCOUNT_NOT_FOUND, () -> service.getAccountDetail("9999999"));
    }

    /** 直近明細は新しい順で、件数の上限で打ち切られる。 */
    @Test
    void historyIsCappedAtLimitNewestFirst() {
        for (int i = 0; i < BankService.HISTORY_LIMIT + 5; i++) {
            service.deposit("1000002", BigDecimal.ONE, null, key());
        }
        List<AccountTransaction> history = service.getRecentTransactions("1000002");
        assertEquals(BankService.HISTORY_LIMIT, history.size());
        assertTrue(history.get(0).getTxId() > history.get(history.size() - 1).getTxId());
    }

    /** 同じ冪等キーの 2 回目は DUPLICATE_REQUEST になり、二重に記帳されない。 */
    @Test
    void duplicateIdempotencyKeyIsRejected() {
        String k = key();
        service.deposit("1000001", new BigDecimal("1000"), null, k);
        assertError(ErrorCode.DUPLICATE_REQUEST, () -> service.deposit("1000001", new BigDecimal("1000"), null, k));
        assertEquals(0, new BigDecimal("1251000").compareTo(balance("1000001")));
    }

    /** 取引可能（ACTIVE）でない口座では取引できない。 */
    @Test
    void frozenAccountCannotTransact() {
        assertError(ErrorCode.ACCOUNT_NOT_ACTIVE,
                () -> service.deposit("1000005", new BigDecimal("100"), null, key()));
        assertError(ErrorCode.ACCOUNT_NOT_ACTIVE,
                () -> service.transfer("1000001", "1000005", new BigDecimal("100"), null, key()));
        assertEquals(0, new BigDecimal("1250000").compareTo(balance("1000001")));
    }

    /** 不正な要求（0 円・小数・上限超過・同じ口座・存在しない口座など）を拒否する。 */
    @Test
    void rejectsInvalidRequests() {
        assertError(ErrorCode.SAME_ACCOUNT,
                () -> service.transfer("1000001", "1000001", new BigDecimal("100"), null, key()));
        assertError(ErrorCode.LIMIT_EXCEEDED,
                () -> service.deposit("1000001", new BigDecimal("10000001"), null, key()));
        assertError(ErrorCode.VALIDATION_ERROR,
                () -> service.deposit("1000001", new BigDecimal("0"), null, key()));
        assertError(ErrorCode.VALIDATION_ERROR,
                () -> service.deposit("1000001", new BigDecimal("1.5"), null, key()));
        assertError(ErrorCode.ACCOUNT_NOT_FOUND,
                () -> service.deposit("9999999", new BigDecimal("100"), null, key()));
        assertError(ErrorCode.ACCOUNT_NOT_FOUND, () -> service.getAccount("9999999"));
    }

    /**
     * 逆方向の振込を同時に大量に実行しても、合計残高が保たれデッドロックしない
     * （口座番号順にロックしているため）。
     */
    @Test
    void concurrentOppositeTransfersKeepTotalAndDoNotDeadlock() throws Exception {
        BigDecimal before = balance("1000001").add(balance("1000003"));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                boolean forward = i % 2 == 0;
                futures.add(pool.submit(() -> forward
                        ? service.transfer("1000001", "1000003", new BigDecimal("1000"), null, key())
                        : service.transfer("1000003", "1000001", new BigDecimal("700"), null, key())));
            }
            // get() で各スレッドの例外（デッドロック検出によるエラーなど）をテストの失敗として受け取る
            for (Future<?> f : futures) f.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, before.compareTo(balance("1000001").add(balance("1000003"))));
        assertEquals(0, new BigDecimal("1244000").compareTo(balance("1000001")));   // 1,250,000 - 20*1000 + 20*700
        assertEquals(0, new BigDecimal("486000").compareTo(balance("1000003")));
    }
}
