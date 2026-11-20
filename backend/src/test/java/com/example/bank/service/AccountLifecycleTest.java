package com.example.bank.service;

import com.example.bank.domain.Account;
import com.example.bank.domain.AccountTransaction;
import com.example.bank.domain.BankException;
import com.example.bank.domain.CloseResult;
import com.example.bank.domain.Customer;
import com.example.bank.domain.DormancyReport;
import com.example.bank.domain.ErrorCode;
import com.example.bank.domain.InterestResult;
import com.example.bank.domain.NewCustomer;
import com.example.bank.domain.SettlementSummary;
import com.example.bank.domain.TransactionPage;
import com.example.bank.domain.TxType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 口座開設・KYC、取引履歴、利息・税、解約、休眠のサービステスト（H2 / Oracle 互換モード）。 */
class AccountLifecycleTest {
    /** 2026-11-20 10:00 JST */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T01:00:00Z"), ZoneId.of("Asia/Tokyo"));
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);

    private BankService bank;
    private CustomerService customers;
    private InterestService interest;
    private AccountLifecycleService lifecycle;

    @BeforeEach
    void setUp() {
        TestDatabase db = TestDatabase.create();
        bank = new BankService(() -> db, CLOCK);
        customers = new CustomerService(() -> db, CLOCK);
        interest = new InterestService(() -> db, CLOCK);
        lifecycle = new AccountLifecycleService(() -> db, CLOCK);
    }

    private static String key() { return UUID.randomUUID().toString(); }

    private static BigDecimal yen(long v) { return BigDecimal.valueOf(v); }

    private static void assertYen(long expected, BigDecimal actual) {
        assertEquals(0, yen(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private static void assertError(ErrorCode expected, Executable call) {
        BankException e = assertThrows(BankException.class, call);
        assertEquals(expected, e.getCode());
    }

    private static NewCustomer applicant(String kana, LocalDate birth) {
        return new NewCustomer("伊藤 健", kana, birth, "060-0001", "北海道札幌市中央区北1条西1-1", "0111234567",
                "MY_NUMBER_CARD", "AB12345678");
    }

    /** 帳簿の整合性: 口座残高 = 最新明細の取引後残高 */
    private void assertLedgerConsistent() {
        for (Account a : bank.listAccounts()) {
            List<AccountTransaction> recent = bank.getRecentTransactions(a.getAccountNo());
            BigDecimal last = recent.isEmpty() ? BigDecimal.ZERO : recent.get(0).getBalanceAfter();
            assertEquals(0, last.compareTo(a.getBalance()), "ledger mismatch on " + a.getAccountNo());
        }
    }

    // ---- 口座開設・本人確認 ----

    @Test
    void accountOpeningRequiresVerifiedKyc() {
        Customer c = customers.register(applicant("イトウ ケン", LocalDate.of(1990, 4, 1)), key());
        assertEquals("PENDING", c.getKycStatus());
        assertEquals("5678", c.getIdDocLast4());   // only the last 4 digits are stored

        assertError(ErrorCode.KYC_NOT_VERIFIED,
                () -> customers.openAccount(c.getCustomerId(), "ORDINARY", yen(10000), key()));

        Customer verified = customers.decideKyc(c.getCustomerId(), "VERIFIED", "マイナンバーカード 原本確認");
        assertEquals("VERIFIED", verified.getKycStatus());
        assertError(ErrorCode.KYC_ALREADY_DECIDED, () -> customers.decideKyc(c.getCustomerId(), "REJECTED", "x"));

        Account account = customers.openAccount(c.getCustomerId(), "ORDINARY", yen(10000), key());
        assertEquals("1000008", account.getAccountNo());
        assertEquals("ACTIVE", account.getStatus());
        assertYen(10000, account.getBalance());
        assertEquals("口座開設時入金", bank.getRecentTransactions("1000008").get(0).getMemo());

        assertError(ErrorCode.ACCOUNT_TYPE_LIMIT,
                () -> customers.openAccount(c.getCustomerId(), "ORDINARY", BigDecimal.ZERO, key()));
        Account savings = customers.openAccount(c.getCustomerId(), "SAVINGS", BigDecimal.ZERO, key());
        assertYen(0, savings.getBalance());
        assertTrue(bank.getRecentTransactions(savings.getAccountNo()).isEmpty());
        assertEquals(2, customers.getCustomer(c.getCustomerId()).getAccounts().size());
        assertLedgerConsistent();
    }

    @Test
    void registrationRules() {
        assertError(ErrorCode.AGE_RESTRICTION,
                () -> customers.register(applicant("ワカモノ タロウ", TODAY.minusYears(18).plusDays(1)), key()));
        customers.register(applicant("ワカモノ タロウ", TODAY.minusYears(18)), key());   // 18th birthday today

        // Same kana + birth date as an existing customer (名寄せ)
        assertError(ErrorCode.CUSTOMER_ALREADY_EXISTS,
                () -> customers.register(applicant("ヤマダ タロウ", LocalDate.of(1980, 5, 12)), key()));

        // A rejected applicant can apply again; rejected customers cannot open accounts
        Customer rejected = customers.register(applicant("サイシンセイ ハナ", LocalDate.of(1985, 1, 1)), key());
        customers.decideKyc(rejected.getCustomerId(), "REJECTED", "書類の有効期限切れ");
        assertError(ErrorCode.KYC_NOT_VERIFIED,
                () -> customers.openAccount(rejected.getCustomerId(), "ORDINARY", BigDecimal.ZERO, key()));
        customers.register(applicant("サイシンセイ ハナ", LocalDate.of(1985, 1, 1)), key());

        String k = key();
        customers.register(applicant("ニジュウ ソウシン", LocalDate.of(1970, 1, 1)), k);
        assertError(ErrorCode.DUPLICATE_REQUEST,
                () -> customers.register(applicant("ニジュウ ソウシン", LocalDate.of(1970, 1, 1)), k));
        assertError(ErrorCode.CUSTOMER_NOT_FOUND, () -> customers.getCustomer("CU9999"));
    }

    // ---- 振替・取引履歴 ----

    @Test
    void ownAccountTransferIsLimitedToSameCustomer() {
        bank.transfer("1000001", "1000002", yen(1000), "振替", key(), true);
        assertError(ErrorCode.NOT_OWN_ACCOUNT, () -> bank.transfer("1000001", "1000003", yen(1000), null, key(), true));
        assertLedgerConsistent();
    }

    @Test
    void historySearchFiltersPagesAndReportsPeriodBalances() {
        bank.deposit("1000001", yen(1000), "a", key());
        bank.withdraw("1000001", yen(300), "b", key());
        bank.deposit("1000001", yen(500), "c", key());

        TransactionPage all = bank.searchTransactions("1000001", null, null, Collections.<TxType>emptyList(), 1, 2);
        assertEquals(5, all.getTotal());   // 2 seeded + 3
        assertEquals(2, all.getItems().size());
        assertTrue(all.isHasNext());
        assertEquals("c", all.getItems().get(0).getMemo());   // newest first
        assertYen(1251200, all.getClosingBalance());

        TransactionPage today = bank.searchTransactions("1000001", TODAY, TODAY,
                Collections.<TxType>emptyList(), 1, 50);
        assertEquals(3, today.getTotal());
        assertYen(1250000, today.getOpeningBalance());
        assertYen(1251200, today.getClosingBalance());
        assertYen(1500, today.getTotalIn());
        assertYen(300, today.getTotalOut());

        TransactionPage withdrawals = bank.searchTransactions("1000001", null, null,
                Arrays.asList(TxType.WITHDRAWAL, TxType.TRANSFER_OUT), 1, 50);
        assertEquals(2, withdrawals.getTotal());

        TransactionPage february = bank.searchTransactions("1000001", LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 2, 28), Collections.<TxType>emptyList(), 1, 50);
        assertEquals(1, february.getTotal());
        assertYen(1300000, february.getOpeningBalance());
        assertYen(1250000, february.getClosingBalance());

        assertError(ErrorCode.ACCOUNT_NOT_FOUND, () -> bank.searchTransactions("9999999", null, null,
                Collections.<TxType>emptyList(), 1, 50));
    }

    // ---- 利息・税 ----

    @Test
    void interestSettlementCreditsNetInterestOnceAndKeepsLastActivity() {
        String lastActivity = bank.getAccount("1000004").getLastActivityAt();
        // 1000004: 120,000 yen since 2026-01-06; settle as of 2026-03-01 -> 2026-01-06..2026-02-28 (54 days)
        InterestResult preview = interest.preview("1000004", LocalDate.of(2026, 3, 1));
        assertFalse(preview.isSettled());
        assertEquals("2026-01-06", preview.getPeriodFrom());
        assertEquals("2026-02-28", preview.getPeriodTo());
        assertEquals(54, preview.getDays());
        assertYen(35, preview.getGrossInterest());   // 120,000 x 54 x 0.2% / 365 = 35.5
        assertYen(5, preview.getNationalTax());
        assertYen(1, preview.getLocalTax());
        assertYen(29, preview.getNetInterest());
        assertYen(120000, bank.getAccount("1000004").getBalance());   // preview does not write

        InterestResult settled = interest.settle("1000004", LocalDate.of(2026, 3, 1));
        assertTrue(settled.isSettled());
        assertYen(120029, bank.getAccount("1000004").getBalance());
        List<AccountTransaction> recent = bank.getRecentTransactions("1000004");
        assertEquals("TAX", recent.get(0).getTxType());
        assertYen(6, recent.get(0).getAmount());
        assertEquals("INTEREST", recent.get(1).getTxType());
        assertYen(35, recent.get(1).getAmount());

        // Same period again: nothing to settle
        assertFalse(interest.settle("1000004", LocalDate.of(2026, 3, 1)).isSettled());
        // Next period starts the day after the settled one. The interest was posted at processing time
        // (2026-11-20, back-dated settlement), so 2026-03-01 still has the pre-interest balance.
        InterestResult next = interest.preview("1000004", LocalDate.of(2026, 3, 2));
        assertEquals("2026-03-01", next.getPeriodFrom());
        assertYen(120000, next.getBalanceDays());
        // ...and the credited interest counts from the day it was posted:
        // 2026-03-01..2026-11-19 (264 days) at 120,000 + 2026-11-20 at 120,029
        InterestResult throughToday = interest.preview("1000004", TODAY.plusDays(1));
        assertEquals(265, throughToday.getDays());
        assertYen(120000L * 264 + 120029, throughToday.getBalanceDays());

        assertEquals(lastActivity, bank.getAccount("1000004").getLastActivityAt());   // interest is not activity
        assertError(ErrorCode.INVALID_BASE_DATE, () -> interest.settle("1000004", TODAY.plusDays(1)));
        assertLedgerConsistent();
    }

    @Test
    void bulkSettlementSkipsClosedAccounts() {
        lifecycle.close("1000004", null, key());
        SettlementSummary summary = interest.settleAll(TODAY);
        assertEquals(7, summary.getResults().size());   // 8 accounts - 1 closed
        assertEquals(7, summary.getSettledCount());
        assertTrue(summary.getTotalGross().signum() > 0);
        assertEquals(0, summary.getTotalGross().subtract(summary.getTotalTax()).compareTo(summary.getTotalNet()));
        assertEquals(0, interest.settleAll(TODAY).getSettledCount());   // idempotent per period
        assertLedgerConsistent();
    }

    // ---- 解約 ----

    @Test
    void closingSettlesInterestAndPaysOutToOwnAccount() {
        BigDecimal payoutBefore = bank.getAccount("1000002").getBalance();
        CloseResult r = lifecycle.close("1000001", "1000002", key());
        assertTrue(r.getInterest().isSettled());
        assertEquals("2026-11-19", r.getInterest().getPeriodTo());   // up to the day before closing
        assertYen(1250000 + r.getInterest().getNetInterest().longValue(), r.getPayoutAmount());

        Account closed = bank.getAccount("1000001");
        assertEquals("CLOSED", closed.getStatus());
        assertYen(0, closed.getBalance());
        assertNotNull(closed.getClosedAt());
        assertEquals(0, payoutBefore.add(r.getPayoutAmount()).compareTo(bank.getAccount("1000002").getBalance()));
        assertEquals("CLOSE_PAYOUT", bank.getRecentTransactions("1000001").get(0).getTxType());

        assertError(ErrorCode.ACCOUNT_NOT_ACTIVE, () -> bank.deposit("1000001", yen(1), null, key()));
        assertError(ErrorCode.ACCOUNT_NOT_ACTIVE, () -> lifecycle.close("1000001", null, key()));
        assertLedgerConsistent();
    }

    @Test
    void closingRules() {
        assertError(ErrorCode.NOT_OWN_ACCOUNT, () -> lifecycle.close("1000001", "1000003", key()));
        assertError(ErrorCode.ACCOUNT_NOT_ACTIVE, () -> lifecycle.close("1000005", null, key()));   // frozen
        assertError(ErrorCode.SAME_ACCOUNT, () -> lifecycle.close("1000001", "1000001", key()));
        assertEquals("ACTIVE", bank.getAccount("1000001").getStatus());   // rolled back

        CloseResult cash = lifecycle.close("1000003", null, key());
        assertNull(cash.getPayoutAccountNo());
        assertTrue(cash.getPayoutAmount().compareTo(yen(480000)) >= 0);
        assertLedgerConsistent();
    }

    // ---- 休眠預金 ----

    @Test
    void dormancyNoticeDormantAndReactivation() {
        DormancyReport report = lifecycle.dormancyReport(TODAY);
        assertEquals(Collections.singletonList("1000007"), accountNos(report.getNoticeTargets()));
        assertEquals(Collections.singletonList("1000006"), accountNos(report.getDormancyTargets()));

        DormancyReport run = lifecycle.runDormancy(TODAY);
        assertEquals(1, run.getNoticed());
        assertEquals(1, run.getMadeDormant());
        assertEquals("DORMANT", bank.getAccount("1000006").getStatus());
        assertNotNull(bank.getAccount("1000007").getDormancyNotifiedAt());
        assertEquals("ACTIVE", bank.getAccount("1000007").getStatus());
        assertEquals(0, lifecycle.runDormancy(TODAY).getMadeDormant());   // nothing left today

        // 1000007 becomes dormant once 10 years have passed and the notice is a month old
        DormancyReport later = lifecycle.dormancyReport(LocalDate.of(2027, 3, 2));
        assertEquals(Collections.singletonList("1000007"), accountNos(later.getDormancyTargets()));
        assertError(ErrorCode.INVALID_BASE_DATE, () -> lifecycle.runDormancy(TODAY.plusDays(1)));

        // Dormant accounts cannot transact until reactivated
        assertError(ErrorCode.ACCOUNT_NOT_ACTIVE, () -> bank.deposit("1000006", yen(1000), null, key()));
        assertError(ErrorCode.ACCOUNT_NOT_DORMANT, () -> lifecycle.reactivate("1000007", key()));
        Account reactivated = lifecycle.reactivate("1000006", key());
        assertEquals("ACTIVE", reactivated.getStatus());
        assertNull(reactivated.getDormantAt());
        bank.deposit("1000006", yen(1000), null, key());

        // Any customer transaction resets the 9-year notice
        bank.deposit("1000007", yen(1), null, key());
        assertNull(bank.getAccount("1000007").getDormancyNotifiedAt());
        assertTrue(lifecycle.dormancyReport(TODAY).getNoticeTargets().isEmpty());
        assertLedgerConsistent();
    }

    private static List<String> accountNos(List<Account> accounts) {
        String[] nos = new String[accounts.size()];
        for (int i = 0; i < nos.length; i++) nos[i] = accounts.get(i).getAccountNo();
        return Arrays.asList(nos);
    }
}
