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

class BankServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-01T01:00:00Z"), ZoneId.of("Asia/Tokyo"));

    private TestDatabase db;
    private BankService service;

    @BeforeEach
    void setUp() {
        db = TestDatabase.create();
        service = new BankService(() -> db, CLOCK);
    }

    private static String key() { return UUID.randomUUID().toString(); }

    private BigDecimal balance(String accountNo) { return service.getAccount(accountNo).getBalance(); }

    private static void assertError(ErrorCode expected, Executable call) {
        BankException e = assertThrows(BankException.class, call);
        assertEquals(expected, e.getCode());
    }

    @Test
    void listsSeededAccountsWithCustomerNames() {
        List<Account> accounts = service.listAccounts();
        assertEquals(7, accounts.size());
        assertEquals("1000001", accounts.get(0).getAccountNo());
        assertEquals("山田 太郎", accounts.get(0).getCustomerName());
        assertEquals(0, new BigDecimal("1250000").compareTo(accounts.get(0).getBalance()));
    }

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

    @Test
    void withdrawWithInsufficientFundsIsRejectedAndRolledBack() {
        String k = key();
        assertError(ErrorCode.INSUFFICIENT_FUNDS,
                () -> service.withdraw("1000004", new BigDecimal("120001"), null, k));
        assertEquals(0, new BigDecimal("120000").compareTo(balance("1000004")));
        // The idempotency key was rolled back too, so the corrected request can reuse it
        service.withdraw("1000004", new BigDecimal("120000"), null, k);
        assertEquals(0, BigDecimal.ZERO.compareTo(balance("1000004")));
    }

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

    @Test
    void historyIsCappedAtLimitNewestFirst() {
        for (int i = 0; i < BankService.HISTORY_LIMIT + 5; i++) {
            service.deposit("1000002", BigDecimal.ONE, null, key());
        }
        List<AccountTransaction> history = service.getRecentTransactions("1000002");
        assertEquals(BankService.HISTORY_LIMIT, history.size());
        assertTrue(history.get(0).getTxId() > history.get(history.size() - 1).getTxId());
    }

    @Test
    void duplicateIdempotencyKeyIsRejected() {
        String k = key();
        service.deposit("1000001", new BigDecimal("1000"), null, k);
        assertError(ErrorCode.DUPLICATE_REQUEST, () -> service.deposit("1000001", new BigDecimal("1000"), null, k));
        assertEquals(0, new BigDecimal("1251000").compareTo(balance("1000001")));
    }

    @Test
    void frozenAccountCannotTransact() {
        assertError(ErrorCode.ACCOUNT_NOT_ACTIVE,
                () -> service.deposit("1000005", new BigDecimal("100"), null, key()));
        assertError(ErrorCode.ACCOUNT_NOT_ACTIVE,
                () -> service.transfer("1000001", "1000005", new BigDecimal("100"), null, key()));
        assertEquals(0, new BigDecimal("1250000").compareTo(balance("1000001")));
    }

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
            for (Future<?> f : futures) f.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, before.compareTo(balance("1000001").add(balance("1000003"))));
        assertEquals(0, new BigDecimal("1244000").compareTo(balance("1000001")));   // 1,250,000 - 20*1000 + 20*700
        assertEquals(0, new BigDecimal("486000").compareTo(balance("1000003")));
    }
}
