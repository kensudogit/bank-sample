package com.example.bank.service;

import com.example.bank.domain.Account;
import com.example.bank.domain.BankException;
import com.example.bank.domain.CloseResult;
import com.example.bank.domain.DormancyReport;
import com.example.bank.domain.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 口座のライフサイクル：解約（利息決算・払出し・入力の検証）、休眠の通知と休眠化、休眠解除。 */
class AccountLifecycleServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
    private TestDatabase db;
    private BankService bank;
    private AccountLifecycleService lifecycle;

    @BeforeEach
    void setUp() {
        db = TestDatabase.create();
        TestClock clock = new TestClock(LocalDateTime.of(2026, 11, 20, 10, 0));
        bank = new BankService(() -> db, clock);
        lifecycle = new AccountLifecycleService(() -> db, clock);
    }

    private static String key() { return UUID.randomUUID().toString(); }

    private static void assertError(ErrorCode expected, Executable call) {
        assertEquals(expected, assertThrows(BankException.class, call).getCode());
    }

    private static List<String> nos(List<Account> accounts) {
        return accounts.stream().map(Account::getAccountNo).collect(Collectors.toList());
    }

    @Test
    void closeIntoOwnAccountPaysOutBalanceAndInterest() {
        BigDecimal before = bank.getAccount("1000002").getBalance();
        CloseResult r = lifecycle.close("1000001", "1000002", key());
        assertEquals("1000001", r.getAccountNo());
        assertEquals("1000002", r.getPayoutAccountNo());
        assertTrue(r.getPayoutAmount().compareTo(new BigDecimal("1250000")) >= 0);
        assertEquals("CLOSED", bank.getAccount("1000001").getStatus());
        assertEquals(0, before.add(r.getPayoutAmount()).compareTo(bank.getAccount("1000002").getBalance()));
    }

    @Test
    void closingRules() {
        assertError(ErrorCode.NOT_OWN_ACCOUNT, () -> lifecycle.close("1000001", "1000003", key()));
        assertError(ErrorCode.SAME_ACCOUNT, () -> lifecycle.close("1000001", "1000001", key()));
        assertError(ErrorCode.ACCOUNT_NOT_ACTIVE, () -> lifecycle.close("1000005", null, key()));
        assertError(ErrorCode.ACCOUNT_NOT_FOUND, () -> lifecycle.close("9999999", null, key()));
        // 失敗した解約は何も変えない
        assertEquals("ACTIVE", bank.getAccount("1000001").getStatus());
    }

    @Test
    void cashPayoutHasNoPayoutAccount() {
        CloseResult r = lifecycle.close("1000004", null, key());
        assertNull(r.getPayoutAccountNo());
        assertNotNull(r.getClosedAt());
        assertNotNull(r.getInterest());
    }

    @Test
    void sameKeyTwiceIsADuplicate() {
        String k = key();
        lifecycle.close("1000004", null, k);
        assertError(ErrorCode.DUPLICATE_REQUEST, () -> lifecycle.close("1000003", null, k));
    }

    @Test
    void dormancyReportAndRun() {
        DormancyReport report = lifecycle.dormancyReport(TODAY);
        assertEquals(TODAY.toString(), report.getAsOf());
        assertEquals(java.util.Collections.singletonList("1000007"), nos(report.getNoticeTargets()));
        assertEquals(java.util.Collections.singletonList("1000006"), nos(report.getDormancyTargets()));
        // 照会だけでは何も変わらない
        assertEquals("ACTIVE", bank.getAccount("1000006").getStatus());

        DormancyReport run = lifecycle.runDormancy(TODAY);
        assertEquals(1, run.getNoticed());
        assertEquals(1, run.getMadeDormant());
        assertEquals("DORMANT", bank.getAccount("1000006").getStatus());
        assertEquals(0, lifecycle.runDormancy(TODAY).getMadeDormant());
    }

    @Test
    void runDormancyRejectsFutureBaseDates() {
        assertError(ErrorCode.INVALID_BASE_DATE, () -> lifecycle.runDormancy(TODAY.plusDays(1)));
    }

    @Test
    void reactivateOnlyDormantAccounts() {
        assertError(ErrorCode.ACCOUNT_NOT_DORMANT, () -> lifecycle.reactivate("1000001", key()));
        lifecycle.runDormancy(TODAY);
        Account a = lifecycle.reactivate("1000006", key());
        assertEquals("ACTIVE", a.getStatus());
        assertNull(a.getDormantAt());
    }
}
