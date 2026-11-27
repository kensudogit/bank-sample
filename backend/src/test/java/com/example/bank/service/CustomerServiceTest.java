package com.example.bank.service;

import com.example.bank.domain.Account;
import com.example.bank.domain.BankException;
import com.example.bank.domain.Customer;
import com.example.bank.domain.ErrorCode;
import com.example.bank.domain.NewCustomer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 顧客：登録（年齢・名寄せ・冪等）、本人確認の判定（職務分離・一度だけ）、口座開設（本人確認済み・種別ごとの上限）。 */
class CustomerServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
    private TestDatabase db;
    private CustomerService customers;

    @BeforeEach
    void setUp() {
        db = TestDatabase.create();
        customers = new CustomerService(() -> db, new TestClock(LocalDateTime.of(2026, 11, 20, 10, 0)));
    }

    private static String key() { return UUID.randomUUID().toString(); }

    private static NewCustomer applicant(String kana, LocalDate birth) {
        return new NewCustomer("伊藤 健", kana, birth, "060-0001", "北海道札幌市中央区北1条西1-1", "0111234567",
                "MY_NUMBER_CARD", "AB12345678");
    }

    private static void assertError(ErrorCode expected, Executable call) {
        assertEquals(expected, assertThrows(BankException.class, call).getCode());
    }

    @Test
    void registerVerifyAndOpen() {
        Customer c = customers.register(applicant("イトウ ケン", LocalDate.of(1990, 4, 1)), key(), "teller1");
        assertEquals("PENDING", c.getKycStatus());
        assertEquals("teller1", c.getRegisteredBy());
        assertError(ErrorCode.KYC_NOT_VERIFIED, () -> customers.openAccount(c.getCustomerId(), "ORDINARY", BigDecimal.TEN, key()));
        Customer verified = customers.decideKyc(c.getCustomerId(), "VERIFIED", "原本確認", "supervisor1");
        assertTrue(verified.isVerified());
        Account a = customers.openAccount(c.getCustomerId(), "ORDINARY", new BigDecimal("10000"), key());
        assertEquals(0, new BigDecimal("10000").compareTo(a.getBalance()));
        assertEquals(1, customers.getCustomer(c.getCustomerId()).getAccounts().size());
        // 同じ種別は 1 口座まで
        assertError(ErrorCode.ACCOUNT_TYPE_LIMIT, () -> customers.openAccount(c.getCustomerId(), "ORDINARY", BigDecimal.ZERO, key()));
    }

    @Test
    void registrationRules() {
        assertError(ErrorCode.AGE_RESTRICTION, () -> customers.register(applicant("ワカモノ", TODAY.minusYears(18).plusDays(1)), key()));
        assertNotNull(customers.register(applicant("ワカモノ", TODAY.minusYears(18)), key()));
        // 既存の顧客（山田 太郎）と同じカナ・生年月日は登録できない
        assertError(ErrorCode.CUSTOMER_ALREADY_EXISTS, () -> customers.register(applicant("ヤマダ タロウ", LocalDate.of(1980, 5, 12)), key()));
        String k = key();
        customers.register(applicant("ニジュウ", LocalDate.of(1970, 1, 1)), k);
        assertError(ErrorCode.DUPLICATE_REQUEST, () -> customers.register(applicant("ニジュウ", LocalDate.of(1970, 1, 1)), k));
    }

    @Test
    void kycDecisionRules() {
        Customer c = customers.register(applicant("イトウ ケン", LocalDate.of(1990, 4, 1)), key(), "supervisor1");
        assertError(ErrorCode.SEGREGATION_OF_DUTIES, () -> customers.decideKyc(c.getCustomerId(), "VERIFIED", "確認", "supervisor1"));
        assertError(ErrorCode.VALIDATION_ERROR, () -> customers.decideKyc(c.getCustomerId(), "MAYBE", "確認", "supervisor2"));
        customers.decideKyc(c.getCustomerId(), "REJECTED", "書類の期限切れ", "supervisor2");
        assertError(ErrorCode.KYC_ALREADY_DECIDED, () -> customers.decideKyc(c.getCustomerId(), "VERIFIED", "x", "supervisor2"));
        assertError(ErrorCode.KYC_NOT_VERIFIED, () -> customers.openAccount(c.getCustomerId(), "ORDINARY", BigDecimal.ZERO, key()));
    }

    @Test
    void lookups() {
        assertTrue(customers.listCustomers().size() >= 4);
        assertEquals("山田 太郎", customers.getCustomer("CU0001").getName());
        assertError(ErrorCode.CUSTOMER_NOT_FOUND, () -> customers.getCustomer("CU9999"));
    }

    @Test
    void checkingIsForCorporateCustomersOnly() {
        assertError(ErrorCode.CORPORATE_ONLY, () -> customers.openAccount("CU0001", "CHECKING", "JPY", BigDecimal.ZERO, key()));
    }
}
