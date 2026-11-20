package com.example.bank.service;

import com.example.bank.domain.Account;
import com.example.bank.domain.AccountTransaction;
import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.domain.InterestResult;
import com.example.bank.service.SecurityAndOperationsTest.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 預金商品・融資・為替・AML・リスク管理のサービステスト（H2 / Oracle 互換モード）。 */
class BankingServicesTest {
    private TestDatabase db;
    private MutableClock clock;
    private BankService bank;
    private CustomerService customers;
    private DepositProductService deposits;
    private InterestService interest;
    private AccountLifecycleService lifecycle;
    private LoanService loans;
    private PaymentService payments;
    private AmlService aml;
    private RiskService risk;

    @BeforeEach
    void setUp() {
        db = TestDatabase.create();
        clock = new MutableClock(LocalDateTime.of(2026, 11, 20, 10, 0));   // Friday, business day
        bank = new BankService(() -> db, clock);
        customers = new CustomerService(() -> db, clock);
        deposits = new DepositProductService(() -> db, clock);
        interest = new InterestService(() -> db, clock);
        lifecycle = new AccountLifecycleService(() -> db, clock);
        loans = new LoanService(() -> db, clock);
        payments = new PaymentService(() -> db, clock);
        aml = new AmlService(() -> db, clock);
        risk = new RiskService(() -> db, clock);
    }

    private static String key() { return UUID.randomUUID().toString(); }

    private static BigDecimal yen(long v) { return BigDecimal.valueOf(v); }

    private static void assertYen(long expected, Object actual) {
        BigDecimal a = actual instanceof BigDecimal ? (BigDecimal) actual : new BigDecimal(actual.toString());
        assertEquals(0, yen(expected).compareTo(a), "expected " + expected + " but was " + actual);
    }

    private static void assertError(ErrorCode expected, Executable call) {
        BankException e = assertThrows(BankException.class, call);
        assertEquals(expected, e.getCode());
    }

    private void sql(String statement) throws Exception {
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute(statement);
        }
    }

    private BigDecimal balance(String accountNo) { return bank.getAccount(accountNo).getBalance(); }

    private long id(Map<String, Object> m, String key) { return new BigDecimal(m.get(key).toString()).longValue(); }

    /** 申込 → 審査（teller1）→ 承認（supervisor1）→ 契約 → 実行。 */
    private Map<String, Object> grant(LoanService.Application a, String accountNo, java.util.function.LongConsumer beforeContract) {
        long appId = id(loans.apply(a, "teller1"), "applicationId");
        loans.screen(appId, "teller1");
        loans.decide(appId, true, "承認", null, "supervisor1");
        if (beforeContract != null) beforeContract.accept(appId);
        long loanId = id(loans.contract(appId, accountNo, 27, "teller1"), "loanId");
        return loans.disburse(loanId, "teller1", key());
    }

    // ===================== deposits =====================

    @Test
    void checkingAccountsAreCorporateOnly() {
        assertError(ErrorCode.CORPORATE_ONLY, () -> customers.openAccount("CU0001", "CHECKING", "JPY", BigDecimal.ZERO, key()));
        // The corporate sample already has a checking account (one per type)
        assertError(ErrorCode.ACCOUNT_TYPE_LIMIT, () -> customers.openAccount("CU9001", "CHECKING", "JPY", BigDecimal.ZERO, key()));
        // Without an overdraft line a checking account cannot go negative
        assertError(ErrorCode.INSUFFICIENT_FUNDS, () -> bank.withdraw("2000001", yen(8_500_001), null, key()));
    }

    @Test
    void timeDepositEarlyCancellationUsesOrdinaryRate() {
        Map<String, Object> td = deposits.createTimeDeposit("1000002", yen(1_000_000), 12, "PAYOUT", key());
        String no = (String) td.get("accountNo");
        assertYen(2_000_000, balance("1000002"));
        assertYen(1_000_000, balance(no));
        assertEquals("TIME", bank.getAccount(no).getAccountType());
        assertYen(4000, td.get("expectedGrossInterest"));   // 0.40% for 12 months
        assertError(ErrorCode.ACCOUNT_TYPE_NOT_ALLOWED, () -> bank.withdraw(no, yen(1), null, key()));
        assertError(ErrorCode.ACCOUNT_TYPE_NOT_ALLOWED, () -> lifecycle.close(no, null, key()));
        assertError(ErrorCode.MINIMUM_AMOUNT, () -> deposits.createTimeDeposit("1000002", yen(9_999), 12, "PAYOUT", key()));

        clock.set(LocalDateTime.of(2027, 2, 28, 10, 0));   // 100 days later
        Map<String, Object> r = deposits.cancelTimeDeposit(no, key());
        assertYen(547, r.get("grossInterest"));            // 1,000,000 x 0.2% x 100/365
        assertYen(83, r.get("nationalTax"));
        assertYen(27, r.get("localTax"));
        assertYen(1_000_437, r.get("payout"));
        assertEquals("CLOSED", bank.getAccount(no).getStatus());
        assertYen(3_000_437, balance("1000002"));
    }

    @Test
    void timeDepositMaturityPayoutAndRollover() {
        String payout = (String) deposits.createTimeDeposit("1000002", yen(1_000_000), 3, "PAYOUT", key()).get("accountNo");
        String roll = (String) deposits.createTimeDeposit("1000002", yen(500_000), 3, "ROLLOVER", key()).get("accountNo");
        clock.set(LocalDateTime.of(2027, 2, 20, 2, 0));
        assertTrue(deposits.processMaturities(LocalDate.of(2027, 2, 19)).isEmpty());   // not due yet
        List<Map<String, Object>> done = deposits.processMaturities(LocalDate.of(2027, 2, 20));
        assertEquals(2, done.size());
        // 1,000,000 x 0.25% x 92/365 = 630; tax 96 + 31
        assertEquals("CLOSED", bank.getAccount(payout).getStatus());
        assertYen(1_500_000 + 1_000_000 + 630 - 96 - 31, balance("1000002"));
        // Rollover: principal grows by the net interest, next maturity 3 months later
        Map<String, Object> rolled = deposits.getTimeDeposit(roll);
        assertEquals("ACTIVE", rolled.get("status"));
        assertEquals("2027-05-20", rolled.get("maturityDate"));
        assertTrue(((BigDecimal) rolled.get("principal")).compareTo(yen(500_000)) > 0);
        assertEquals(0, ((BigDecimal) rolled.get("principal")).compareTo(balance(roll)));
        assertTrue(deposits.processMaturities(LocalDate.of(2027, 2, 20)).isEmpty());   // once only
    }

    @Test
    void noticeDepositNeedsNoticeAndHoldingPeriod() {
        assertError(ErrorCode.MINIMUM_AMOUNT, () -> customers.openAccount("CU0001", "NOTICE", "JPY", yen(49_999), key()));
        Account notice = customers.openAccount("CU0001", "NOTICE", "JPY", yen(100_000), key());
        String no = notice.getAccountNo();
        assertError(ErrorCode.NOTICE_REQUIRED, () -> bank.withdraw(no, yen(10_000), null, key()));
        Map<String, Object> n = deposits.notifyWithdrawal(no, yen(50_000));
        assertEquals("2026-11-27", n.get("availableFrom"));   // 7-day holding period from 11/20
        clock.set(LocalDateTime.of(2026, 11, 26, 10, 0));
        assertError(ErrorCode.NOTICE_REQUIRED, () -> bank.withdraw(no, yen(50_000), null, key()));
        clock.set(LocalDateTime.of(2026, 11, 27, 10, 0));
        assertError(ErrorCode.NOTICE_REQUIRED, () -> bank.withdraw(no, yen(60_000), null, key()));   // more than noticed
        bank.withdraw(no, yen(50_000), null, key());
        assertError(ErrorCode.NOTICE_REQUIRED, () -> bank.withdraw(no, yen(1_000), null, key()));    // notice used up
    }

    @Test
    void foreignCurrencyExchangeAndRules() {
        Account usd = customers.openAccount("CU0001", "FOREIGN", "USD", BigDecimal.ZERO, key());
        assertEquals("USD", usd.getCurrency());
        assertError(ErrorCode.ACCOUNT_TYPE_LIMIT, () -> customers.openAccount("CU0001", "FOREIGN", "USD", BigDecimal.ZERO, key()));
        Map<String, Object> buy = deposits.exchange("1000001", usd.getAccountNo(), "100.00", key());
        assertYen(15_330, buy.get("yenAmount"));             // TTS 153.30
        assertYen(1_250_000 - 15_330, balance("1000001"));
        assertYen(10_000, balance(usd.getAccountNo()));      // cents
        Map<String, Object> sell = deposits.exchange(usd.getAccountNo(), "1000001", "40.50", key());
        assertYen(6_127, sell.get("yenAmount"));             // TTB 151.30
        assertYen(5_950, balance(usd.getAccountNo()));

        assertError(ErrorCode.ACCOUNT_TYPE_NOT_ALLOWED, () -> bank.deposit(usd.getAccountNo(), yen(100), null, key()));
        assertError(ErrorCode.ACCOUNT_TYPE_NOT_ALLOWED, () -> bank.transfer(usd.getAccountNo(), "1000002", yen(1), null, key()));
        assertError(ErrorCode.NOT_OWN_ACCOUNT, () -> deposits.exchange("1000003", usd.getAccountNo(), "1.00", key()));
        assertError(ErrorCode.ACCOUNT_NOT_EMPTY, () -> lifecycle.close(usd.getAccountNo(), null, key()));

        // Interest on the USD account at the USD rate (2.00%), in cents
        clock.set(LocalDateTime.of(2027, 11, 20, 10, 0));
        InterestResult r = interest.settle(usd.getAccountNo(), LocalDate.of(2027, 11, 20));
        assertTrue(r.isSettled());
        assertYen(119, r.getGrossInterest());   // 5,950 cents x 2% x 365/365 = 119 cents
        assertYen(18, r.getNationalTax());
    }

    // ===================== loans =====================

    private LoanService.Application housing() {
        LoanService.Application a = new LoanService.Application();
        a.customerId = "CU0001";
        a.product = "HOUSING";
        a.amount = yen(30_000_000);
        a.termMonths = 240;
        a.repaymentMethod = "EQUAL_PAYMENT";
        a.purpose = "自宅購入";
        a.annualIncome = yen(9_000_000);
        a.collateralValue = yen(45_000_000);
        return a;
    }

    @Test
    void housingLoanFullLifecycle() throws Exception {
        long appId = id(loans.apply(housing(), "teller1"), "applicationId");
        Map<String, Object> screened = loans.screen(appId, "teller1");
        assertEquals("SCREENED", screened.get("status"));
        assertEquals("APPROVE", screened.get("recommendation"));
        assertError(ErrorCode.SEGREGATION_OF_DUTIES, () -> loans.decide(appId, true, null, null, "teller1"));
        loans.decide(appId, true, "承認", null, "supervisor1");
        assertError(ErrorCode.LOAN_RULE_VIOLATION, () -> loans.contract(appId, "1000001", 27, "teller1"));   // no real estate
        loans.addCollateral(appId, "REAL_ESTATE", "東京都千代田区 マンション 第1順位抵当権", yen(45_000_000), 1, null, "teller1");
        loans.addCollateral(appId, "GUARANTEE_CO", "サンプル保証株式会社", BigDecimal.ZERO, null, "サンプル保証", "teller1");
        long loanId = id(loans.contract(appId, "1000001", 27, "teller1"), "loanId");
        Map<String, Object> loan = loans.disburse(loanId, "teller1", key());
        assertEquals("ACTIVE", loan.get("status"));
        assertYen(31_250_000, balance("1000001"));
        List<?> schedule = (List<?>) loan.get("schedule");
        assertEquals(240, schedule.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) schedule.get(0);
        assertEquals("2026-12-28", first.get("dueDate"));   // 12/27 is a Sunday -> next business day

        // Auto-debit on the due date
        clock.set(LocalDateTime.of(2026, 12, 28, 2, 0));
        Map<String, Object> run = loans.processDue(LocalDate.of(2026, 12, 28));
        assertEquals(1, run.get("autoDebited"));
        Map<String, Object> after = loans.getLoan(loanId);
        assertEquals("ACTIVE", after.get("status"));
        assertTrue(((BigDecimal) after.get("outstanding")).compareTo(yen(30_000_000)) < 0);

        // Empty the account: the next installments become overdue -> delinquent -> special attention (90+ days)
        sql("UPDATE account SET balance = 0 WHERE account_no = '1000001'");
        clock.set(LocalDateTime.of(2027, 5, 1, 2, 0));
        loans.processDue(LocalDate.of(2027, 5, 1));
        Map<String, Object> late = loans.getLoan(loanId);
        assertEquals("DELINQUENT", late.get("status"));
        assertEquals("SPECIAL", late.get("borrowerClass"));   // oldest overdue 2027-01-27 -> 94 days
        assertError(ErrorCode.LOAN_STATE_INVALID, () -> loans.prepay(loanId, yen(100_000), "teller1", key()));
        assertError(ErrorCode.LOAN_RULE_VIOLATION, () -> loans.writeOff(loanId, "回収不能", "supervisor1"));
        loans.addCollectionAction(loanId, "CALL", "本人に電話。来週入金予定", LocalDate.of(2027, 5, 8), "teller1");

        // Customer pays everything due: late fees, interest and principal are applied, delinquency clears
        sql("UPDATE account SET balance = 2000000 WHERE account_no = '1000001'");
        BigDecimal dueNow = (BigDecimal) late.get("dueNow");
        loans.repay(loanId, dueNow.add(yen(50_000)), "teller1", key());   // the extra covers the late charges
        Map<String, Object> cured = loans.getLoan(loanId);
        assertEquals("ACTIVE", cured.get("status"));
        assertEquals("NORMAL", cured.get("borrowerClass"));
        assertEquals(1, ((List<?>) cured.get("collectionActions")).size());

        // Partial prepayment reduces the remaining installments
        BigDecimal outstanding = (BigDecimal) cured.get("outstanding");
        loans.prepay(loanId, yen(1_000_000), "teller1", key());
        Map<String, Object> pre = loans.getLoan(loanId);
        assertEquals(0, outstanding.subtract(yen(1_000_000)).compareTo((BigDecimal) pre.get("outstanding")));
    }

    @Test
    void cardLoanRevolvingWithTotalVolumeRule() {
        LoanService.Application a = new LoanService.Application();
        a.customerId = "CU0003";
        a.product = "CARD";
        a.amount = yen(500_000);
        a.annualIncome = yen(6_000_000);
        Map<String, Object> loan = grant(a, "1000004", null);
        long loanId = id(loan, "loanId");
        assertYen(0, loan.get("outstanding"));
        loans.draw(loanId, yen(200_000), "suzuki", key());
        assertYen(320_000, balance("1000004"));
        assertError(ErrorCode.CREDIT_LIMIT_EXCEEDED, () -> loans.draw(loanId, yen(300_001), "suzuki", key()));
        clock.set(LocalDateTime.of(2026, 12, 20, 10, 0));   // 30 days later
        BigDecimal rate = (BigDecimal) loans.getLoan(loanId).get("annualRate");
        long expectedInterest = LoanMath.dailyInterest(yen(200_000), rate, 30).longValue();
        loans.repay(loanId, yen(10_000), "suzuki", key());
        Map<String, Object> after = loans.getLoan(loanId);
        assertYen(200_000 - (10_000 - expectedInterest), after.get("outstanding"));   // interest first

        LoanService.Application tooMuch = new LoanService.Application();
        tooMuch.customerId = "CU0002";
        tooMuch.product = "CARD";
        tooMuch.amount = yen(3_000_000);
        tooMuch.annualIncome = yen(6_000_000);
        long appId = id(loans.apply(tooMuch, "teller1"), "applicationId");
        assertEquals("DECLINE", loans.screen(appId, "teller1").get("recommendation"));
        assertError(ErrorCode.LOAN_RULE_VIOLATION, () -> loans.decide(appId, true, "", null, "supervisor1"));
    }

    @Test
    void overdraftOnCheckingAccountAndInterest() {
        LoanService.Application a = new LoanService.Application();
        a.customerId = "CU9001";
        a.product = "OVERDRAFT";
        a.amount = yen(5_000_000);
        a.annualIncome = yen(60_000_000);
        grant(a, "2000001", null);
        assertYen(5_000_000, bank.getAccount("2000001").getOverdraftLimit());
        bank.withdraw("2000001", yen(9_500_000), "仕入代金", key());
        assertYen(-1_000_000, balance("2000001"));
        assertError(ErrorCode.INSUFFICIENT_FUNDS, () -> bank.withdraw("2000001", yen(4_000_001), null, key()));
        assertError(ErrorCode.ACCOUNT_NOT_EMPTY, () -> lifecycle.close("2000001", null, key()));

        clock.set(LocalDateTime.of(2026, 12, 21, 10, 0));
        InterestResult r = interest.settle("2000001", LocalDate.of(2026, 12, 21));
        assertTrue(r.getNote().contains("当座貸越利息"));
        List<AccountTransaction> recent = bank.getRecentTransactions("2000001");
        assertEquals("OD_INTEREST", recent.get(0).getTxType());
        assertTrue(balance("2000001").compareTo(yen(-1_000_000)) < 0);
    }

    @Test
    void syndicatedLoanNeedsMatchingParticipants() {
        LoanService.Application a = new LoanService.Application();
        a.customerId = "CU9001";
        a.product = "SYNDICATED";
        a.amount = yen(100_000_000);
        a.syndicateTotal = yen(300_000_000);
        a.termMonths = 60;
        a.repaymentMethod = "EQUAL_PRINCIPAL";
        a.annualIncome = yen(900_000_000);
        long appId = id(loans.apply(a, "teller1"), "applicationId");
        loans.screen(appId, "teller1");
        loans.decide(appId, true, "協調融資（アレンジャー）", null, "supervisor1");
        loans.addParticipant(appId, LoanService.OUR_BANK, yen(100_000_000), "ARRANGER");
        loans.addParticipant(appId, "協調銀行A", yen(120_000_000), "PARTICIPANT");
        assertError(ErrorCode.LOAN_RULE_VIOLATION, () -> loans.contract(appId, "2000001", 10, "teller1"));
        loans.addParticipant(appId, "協調銀行B", yen(80_000_000), "PARTICIPANT");
        long loanId = id(loans.contract(appId, "2000001", 10, "teller1"), "loanId");
        Map<String, Object> loan = loans.disburse(loanId, "teller1", key());
        assertYen(100_000_000, loan.get("outstanding"));
        assertEquals(3, ((List<?>) loan.get("participants")).size());
        assertError(ErrorCode.CORPORATE_ONLY, () -> {
            LoanService.Application b = new LoanService.Application();
            b.customerId = "CU0001";
            b.product = "BUSINESS";
            b.amount = yen(5_000_000);
            b.termMonths = 36;
            b.repaymentMethod = "EQUAL_PAYMENT";
            b.annualIncome = yen(9_000_000);
            loans.apply(b, "teller1");
        });
    }

    // ===================== payments =====================

    @Test
    void domesticRemittanceZenginAndReturn() {
        Map<String, Object> r = payments.sendDomestic("1000001", "0005", "123", "1", "7654321", "スズキ イチロウ",
                yen(50_000), key(), "yamada");
        assertYen(440, r.get("fee"));
        assertEquals("2026-11-20", r.get("valueDate"));
        assertEquals("ｽｽﾞｷ ｲﾁﾛｳ", r.get("destNameKana"));
        assertYen(1_250_000 - 50_440, balance("1000001"));
        Map<String, Object> small = payments.sendDomestic("1000001", "0001", "001", "1", "1111111", "タナカ",
                yen(1_000), key(), "yamada");
        assertYen(330, small.get("fee"));
        assertError(ErrorCode.BANK_NOT_FOUND, () -> payments.sendDomestic("1000001", "1234", "001", "1", "1111111", "タナカ",
                yen(1_000), key(), "yamada"));

        // After the 15:00 cut-off: next business day (Mon 11/23 is a holiday -> Tue 11/24)
        clock.set(LocalDateTime.of(2026, 11, 20, 15, 30));
        Map<String, Object> late = payments.sendDomestic("1000001", "0009", "200", "1", "2222222", "ヤマダ",
                yen(5_000), key(), "yamada");
        assertEquals("2026-11-24", late.get("valueDate"));

        Map<String, Object> batch = payments.createZenginBatch("supervisor1");
        assertYen(2, batch.get("recordCount"));   // the post-cut-off one waits for its value date
        for (String line : ((String) batch.get("content")).split("\r\n")) assertEquals(120, line.length());

        long remitId = id(r, "remitId");
        Map<String, Object> returned = payments.returnDomestic(remitId, "受取人口座相違", "supervisor1");
        assertEquals("RETURNED", returned.get("status"));
        assertYen(1_250_000 - 440 - 1_330 - 5_330, balance("1000001"));   // amount refunded, fee kept
        long lateId = id(late, "remitId");
        payments.cancelDomestic(lateId, "yamada");
        assertYen(1_250_000 - 440 - 1_330, balance("1000001"));          // pending: amount + fee refunded
    }

    @Test
    void incomingRemittanceMatchesNameOrIsHeld() {
        PaymentService.Incoming ok = new PaymentService.Incoming();
        ok.bankCode = "0005";
        ok.remitterNameKana = "ｶ)ﾃｽﾄｼﾖｳｼﾞ";
        ok.accountNo = "1000003";
        ok.nameKana = "ｻﾄｳ ﾊﾅｺ";
        ok.amount = yen(120_000);
        PaymentService.Incoming wrong = new PaymentService.Incoming();
        wrong.bankCode = "0005";
        wrong.remitterNameKana = "ﾃｽﾄ";
        wrong.accountNo = "1000003";
        wrong.nameKana = "ｻﾄｳ ﾀﾛｳ";
        wrong.amount = yen(30_000);
        List<Map<String, Object>> res = payments.receiveIncoming(Arrays.asList(ok, wrong), "supervisor1");
        assertEquals("CREDITED", res.get(0).get("status"));
        assertEquals("HELD", res.get(1).get("status"));
        assertYen(600_000, balance("1000003"));
        payments.resolveHeld(id(res.get(1), "remitId"), "1000003", true, "supervisor1");
        assertYen(630_000, balance("1000003"));
    }

    @Test
    void directDebitMandateBillingAndProcessing() {
        long mandate = id(payments.registerMandate("1000004", "ELEC01", "E-0001", "teller1"), "mandateId");
        payments.registerMandate("1000004", "GAS01", "G-0001", "teller1");
        assertError(ErrorCode.DUPLICATE_REQUEST, () -> payments.registerMandate("1000003", "ELEC01", "E-0001", "teller1"));
        PaymentService.Billing b1 = billing("ELEC01", "E-0001", 8_000);
        PaymentService.Billing b2 = billing("GAS01", "G-0001", 1_000_000);
        PaymentService.Billing b3 = billing("ELEC01", "E-9999", 100);
        List<Map<String, Object>> imported = payments.importBilling(Arrays.asList(b1, b2, b3));
        assertEquals("契約なし", imported.get(2).get("result"));
        Map<String, Object> result = payments.processDirectDebits(LocalDate.of(2026, 11, 20));
        assertEquals(1, result.get("paid"));
        assertEquals(1, result.get("failed"));   // insufficient funds
        assertYen(112_000, balance("1000004"));
        payments.cancelMandate(mandate);
        assertEquals("FAILED", payments.listDebitRequests(null).get(0).get("status"));
    }

    private static PaymentService.Billing billing(String biller, String payer, long amount) {
        PaymentService.Billing b = new PaymentService.Billing();
        b.billerCode = biller;
        b.payerNumber = payer;
        b.amount = BigDecimal.valueOf(amount);
        b.dueDate = LocalDate.of(2026, 11, 20);
        return b;
    }

    @Test
    void foreignRemittanceScreeningAndHold() {
        PaymentService.ForeignOrder o = new PaymentService.ForeignOrder();
        o.accountNo = "1000002";
        o.feeAccountNo = "1000001";
        o.currency = "USD";
        o.amount = "1000.00";
        o.beneficiaryName = "John Smith";
        o.beneficiaryAccount = "123456789";
        o.swiftBic = "CHASUS33";
        o.country = "US";
        o.purpose = "Tuition";
        Map<String, Object> sent = payments.sendForeign(o, key(), "yamada");
        assertEquals("SENT", sent.get("status"));
        assertYen(153_300, sent.get("debitAmount"));   // USD 1,000 x TTS 153.30
        assertTrue(((String) sent.get("message")).contains(":32A:261120USD1000,00"));
        assertYen(3_000_000 - 153_300, balance("1000002"));
        assertYen(1_250_000 - 4_000, balance("1000001"));

        o.beneficiaryName = "Sampleov Ivan Petrov";
        o.country = "RU";
        Map<String, Object> held = payments.sendForeign(o, key(), "yamada");
        assertEquals("HELD", held.get("status"));
        assertTrue(((String) held.get("screeningResult")).contains("制裁リスト"));
        assertEquals(1, aml.listAlerts("OPEN").stream().filter(a -> "SANCTIONS".equals(a.get("ruleCode"))).count());
        payments.decideForeign(id(held, "remitId"), false, "制裁リスト該当のため謝絶", "supervisor1");
        assertYen(3_000_000 - 153_300, balance("1000002"));   // refunded
        assertYen(1_250_000 - 4_000, balance("1000001"));

        o.beneficiaryName = "Demo Trading";
        o.country = "KP";
        assertEquals("HELD", payments.sendForeign(o, key(), "yamada").get("status"));
        assertEquals(1, aml.listAlerts(null).stream().filter(a -> "HIGH_RISK_COUNTRY".equals(a.get("ruleCode"))).count());
    }

    // ===================== AML / risk / CRM =====================

    @Test
    void amlTransactionMonitoring() {
        bank.deposit("1000001", yen(2_500_000), "現金", key());                          // LARGE_CASH
        for (int i = 0; i < 3; i++) bank.deposit("1000003", yen(700_000), null, key());  // STRUCTURING
        bank.transfer("1000003", "1000004", yen(2_000_000), null, key());               // RAPID_MOVEMENT
        Map<String, Object> scan = aml.scan();
        assertTrue(((Number) scan.get("alerts")).intValue() >= 3);
        List<Map<String, Object>> alerts = aml.listAlerts("OPEN");
        assertTrue(alerts.stream().anyMatch(a -> "LARGE_CASH".equals(a.get("ruleCode"))));
        assertTrue(alerts.stream().anyMatch(a -> "STRUCTURING".equals(a.get("ruleCode"))));
        assertTrue(alerts.stream().anyMatch(a -> "RAPID_MOVEMENT".equals(a.get("ruleCode"))));
        assertEquals(0, ((Number) aml.scan().get("alerts")).intValue());   // incremental, no duplicates

        long alertId = id(alerts.get(0), "alertId");
        aml.updateAlert(alertId, "INVESTIGATING", null, "supervisor1");
        assertError(ErrorCode.VALIDATION_ERROR, () -> aml.updateAlert(alertId, "REPORTED", "", "supervisor1"));
        assertEquals("REPORTED", aml.updateAlert(alertId, "REPORTED", "疑わしい取引の届出を提出", "supervisor1").get("status"));
    }

    @Test
    void riskDashboardAndCustomer360() {
        grant(housing(), "1000001", appId ->
                loans.addCollateral(appId, "REAL_ESTATE", "土地建物", yen(45_000_000), 1, null, "teller1"));
        Map<String, Object> d = risk.dashboard();
        @SuppressWarnings("unchecked")
        Map<String, Object> credit = (Map<String, Object>) d.get("credit");
        assertEquals(1, credit.get("loanCount"));
        assertYen(30_000_000, credit.get("totalOutstanding"));
        assertTrue(((BigDecimal) credit.get("expectedLoss")).signum() > 0);
        @SuppressWarnings("unchecked")
        Map<String, Object> liquidity = (Map<String, Object>) d.get("liquidity");
        assertTrue(((BigDecimal) liquidity.get("loanToDepositRatio")).signum() > 0);

        risk.addNote("CU0001", "VISIT", "住宅ローン実行のご挨拶", "teller1");
        Map<String, Object> c360 = risk.customer360("CU0001");
        assertEquals(1, ((List<?>) c360.get("loans")).size());
        assertEquals(1, ((List<?>) c360.get("notes")).size());
        assertEquals("プライベート", c360.get("segment"));   // > 10M in deposits after the loan
    }
}
