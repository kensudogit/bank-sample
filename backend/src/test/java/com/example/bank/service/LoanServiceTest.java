package com.example.bank.service;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 融資：債務者区分、審査と決裁の職務分離、カードローンの極度額、法人のみの商品、所有者の照会。 */
class LoanServiceTest {
    private TestDatabase db;
    private BankService bank;
    private LoanService loans;

    @BeforeEach
    void setUp() {
        db = TestDatabase.create();
        TestClock clock = new TestClock(LocalDateTime.of(2026, 11, 20, 10, 0));
        bank = new BankService(() -> db, clock);
        loans = new LoanService(() -> db, clock);
    }

    private static String key() { return UUID.randomUUID().toString(); }

    private static BigDecimal yen(long v) { return BigDecimal.valueOf(v); }

    private static long id(Map<String, Object> m, String k) { return new BigDecimal(m.get(k).toString()).longValue(); }

    private static void assertError(ErrorCode expected, Executable call) {
        assertEquals(expected, assertThrows(BankException.class, call).getCode());
    }

    private static LoanService.Application card(String customerId, long amount, long income) {
        LoanService.Application a = new LoanService.Application();
        a.customerId = customerId;
        a.product = "CARD";
        a.amount = yen(amount);
        a.annualIncome = yen(income);
        return a;
    }

    @Test
    void borrowerClassByDaysPastDue() {
        // 1 日でも延滞すれば要注意先、90 日で要管理先、180 日で破綻懸念先、365 日で実質破綻先
        assertEquals("NORMAL", LoanService.borrowerClass(0));
        assertEquals("WATCH", LoanService.borrowerClass(1));
        assertEquals("WATCH", LoanService.borrowerClass(89));
        assertEquals("SPECIAL", LoanService.borrowerClass(90));
        assertEquals("SPECIAL", LoanService.borrowerClass(179));
        assertEquals("DOUBTFUL", LoanService.borrowerClass(180));
        assertEquals("DOUBTFUL", LoanService.borrowerClass(364));
        assertEquals("DEFACTO", LoanService.borrowerClass(365));
    }

    @Test
    void decisionNeedsAnotherPersonThanTheScreener() {
        long appId = id(loans.apply(card("CU0003", 500_000, 6_000_000), "teller1"), "applicationId");
        assertEquals("SCREENED", loans.screen(appId, "teller1").get("status"));
        assertError(ErrorCode.SEGREGATION_OF_DUTIES, () -> loans.decide(appId, true, null, null, "teller1"));
        loans.decide(appId, true, "承認", null, "supervisor1");
        assertEquals("CU0003", loans.applicationOwner(appId));
        assertFalse(loans.listApplications("CU0003", null).isEmpty());
        assertTrue(loans.listApplications("CU0001", null).isEmpty());
    }

    @Test
    void cardLoanDrawsWithinTheLimit() {
        long appId = id(loans.apply(card("CU0003", 500_000, 6_000_000), "teller1"), "applicationId");
        loans.screen(appId, "teller1");
        loans.decide(appId, true, "承認", null, "supervisor1");
        long loanId = id(loans.contract(appId, "1000004", 27, "teller1"), "loanId");
        loans.disburse(loanId, "teller1", key());
        loans.draw(loanId, yen(200_000), "suzuki", key());
        assertEquals(0, yen(320_000).compareTo(bank.getAccount("1000004").getBalance()));
        assertError(ErrorCode.CREDIT_LIMIT_EXCEEDED, () -> loans.draw(loanId, yen(300_001), "suzuki", key()));
        assertEquals("CU0003", loans.loanOwner(loanId));
        assertEquals(0, yen(200_000).compareTo((BigDecimal) loans.getLoan(loanId).get("outstanding")));
    }

    @Test
    void declinedApplicationCannotBeApproved() {
        long appId = id(loans.apply(card("CU0002", 3_000_000, 6_000_000), "teller1"), "applicationId");
        assertEquals("DECLINE", loans.screen(appId, "teller1").get("recommendation"));
        assertError(ErrorCode.LOAN_RULE_VIOLATION, () -> loans.decide(appId, true, "", null, "supervisor1"));
    }

    @Test
    void businessLoansAreForCorporateCustomers() {
        LoanService.Application b = new LoanService.Application();
        b.customerId = "CU0001";
        b.product = "BUSINESS";
        b.amount = yen(5_000_000);
        b.termMonths = 36;
        b.repaymentMethod = "EQUAL_PAYMENT";
        b.annualIncome = yen(9_000_000);
        assertError(ErrorCode.CORPORATE_ONLY, () -> loans.apply(b, "teller1"));
    }

    @Test
    void unknownLoanIsNotFound() {
        assertError(ErrorCode.LOAN_NOT_FOUND, () -> loans.getLoan(999_999));
    }
}
