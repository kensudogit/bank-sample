package com.example.bank.service;

import com.example.bank.domain.Account;
import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 預金商品：定期預金（作成・最低額・照会）、通知預金の払出し通知、外貨の相場と両替。 */
class DepositProductServiceTest {
    private TestDatabase db;
    private BankService bank;
    private CustomerService customers;
    private DepositProductService deposits;

    @BeforeEach
    void setUp() {
        db = TestDatabase.create();
        TestClock clock = new TestClock(LocalDateTime.of(2026, 11, 20, 10, 0));
        bank = new BankService(() -> db, clock);
        customers = new CustomerService(() -> db, clock);
        deposits = new DepositProductService(() -> db, clock);
    }

    private static String key() { return UUID.randomUUID().toString(); }

    private static BigDecimal yen(long v) { return BigDecimal.valueOf(v); }

    private static void assertYen(long expected, Object actual) {
        assertEquals(0, yen(expected).compareTo(new BigDecimal(actual.toString())), "expected " + expected + " but was " + actual);
    }

    private static void assertError(ErrorCode expected, Executable call) {
        assertEquals(expected, assertThrows(BankException.class, call).getCode());
    }

    @Test
    void timeDepositRatesAreListed() {
        assertEquals(5, deposits.timeDepositRates().size());
    }

    @Test
    void createTimeDepositMovesFundsAndFixesTheRate() {
        Map<String, Object> td = deposits.createTimeDeposit("1000002", yen(1_000_000), 12, "PAYOUT", key());
        String no = (String) td.get("accountNo");
        assertYen(4000, td.get("expectedGrossInterest"));
        assertYen(2_000_000, bank.getAccount("1000002").getBalance());
        assertEquals("TIME", bank.getAccount(no).getAccountType());
        Map<String, Object> got = deposits.getTimeDeposit(no);
        assertEquals("ACTIVE", got.get("status"));
        assertEquals("2027-11-20", got.get("maturityDate"));
    }

    @Test
    void timeDepositRules() {
        assertError(ErrorCode.MINIMUM_AMOUNT, () -> deposits.createTimeDeposit("1000002", yen(9_999), 12, "PAYOUT", key()));
        assertError(ErrorCode.INSUFFICIENT_FUNDS, () -> deposits.createTimeDeposit("1000004", yen(1_000_000), 12, "PAYOUT", key()));
        assertError(ErrorCode.VALIDATION_ERROR, () -> deposits.createTimeDeposit("1000002", yen(100_000), 7, "PAYOUT", key()));
    }

    @Test
    void noticeDepositNoticeHasAHoldingPeriod() {
        Account notice = customers.openAccount("CU0001", "NOTICE", "JPY", yen(100_000), key());
        Map<String, Object> n = deposits.notifyWithdrawal(notice.getAccountNo(), yen(50_000));
        assertEquals("2026-11-27", n.get("availableFrom"));
        List<Map<String, Object>> list = deposits.listNotices(notice.getAccountNo());
        assertEquals(1, list.size());
        long id = new BigDecimal(list.get(0).get("noticeId").toString()).longValue();
        deposits.cancelNotice(notice.getAccountNo(), id);
        assertEquals("CANCELLED", deposits.listNotices(notice.getAccountNo()).get(0).get("status"));
    }

    @Test
    void exchangeBuysAtTtsAndSellsAtTtb() {
        Account usd = customers.openAccount("CU0001", "FOREIGN", "USD", BigDecimal.ZERO, key());
        assertYen(15_330, deposits.exchange("1000001", usd.getAccountNo(), "100.00", key()).get("yenAmount"));
        assertYen(10_000, bank.getAccount(usd.getAccountNo()).getBalance());   // セント
        assertYen(6_127, deposits.exchange(usd.getAccountNo(), "1000001", "40.50", key()).get("yenAmount"));
        assertError(ErrorCode.NOT_OWN_ACCOUNT, () -> deposits.exchange("1000003", usd.getAccountNo(), "1.00", key()));
    }

    @Test
    void fxRatesCanBeUpdated() {
        assertEquals(2, deposits.fxRates().size());
        List<Map<String, Object>> updated = deposits.updateFxRate("USD", new BigDecimal("150.00"), new BigDecimal("1.00"), "svc-mule");
        Map<String, Object> usd = updated.stream().filter(r -> "USD".equals(r.get("currency"))).findFirst()
                .orElseThrow(AssertionError::new);
        assertEquals(0, new BigDecimal("150.00").compareTo(new BigDecimal(usd.get("ttm").toString())));
    }
}
