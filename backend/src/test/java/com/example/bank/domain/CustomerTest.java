package com.example.bank.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 顧客：本人確認の判定、法人・個人の区分、口座一覧の付け替え。 */
class CustomerTest {
    private static Customer customer(String kyc, List<Account> accounts) {
        return new Customer("CU0001", "山田 太郎", "ヤマダ タロウ", "1980-01-01", "100-0001", "東京都", "03-0000-0000",
                "DRIVER_LICENSE", "1234", kyc, null, null, "2020-01-01", "teller1", null, accounts);
    }

    @Test
    void nullAccountsBecomeEmptyList() {
        assertTrue(customer("PENDING", null).getAccounts().isEmpty());
    }

    @Test
    void verifiedOnlyWhenKycIsVerified() {
        assertTrue(customer("VERIFIED", null).isVerified());
        assertFalse(customer("PENDING", null).isVerified());
        assertFalse(customer("REJECTED", null).isVerified());
    }

    @Test
    void customerTypeDefaultsToIndividualAndNullResetsIt() {
        Customer c = customer("VERIFIED", null);
        assertEquals("INDIVIDUAL", c.getCustomerType());
        assertFalse(c.isCorporate());
        assertTrue(c.withCustomerType("CORPORATE").isCorporate());
        assertEquals("INDIVIDUAL", c.withCustomerType(null).getCustomerType());
    }

    @Test
    void withAccountsReturnsCopyKeepingFieldsAndType() {
        Customer c = customer("VERIFIED", null).withCustomerType("CORPORATE");
        List<Account> accounts = Collections.singletonList(new Account("1000001", "CU0001", "山田 太郎", "ORDINARY",
                BigDecimal.ZERO, "ACTIVE", null, null, null, null, null, null));
        Customer copy = c.withAccounts(accounts);
        assertNotSame(c, copy);
        assertSame(accounts, copy.getAccounts());
        assertTrue(c.getAccounts().isEmpty());
        assertTrue(copy.isCorporate());
        assertEquals("CU0001", copy.getCustomerId());
        assertEquals("山田 太郎", copy.getName());
        assertEquals("ヤマダ タロウ", copy.getNameKana());
        assertEquals("1980-01-01", copy.getBirthDate());
        assertEquals("100-0001", copy.getPostalCode());
        assertEquals("東京都", copy.getAddress());
        assertEquals("03-0000-0000", copy.getPhone());
        assertEquals("DRIVER_LICENSE", copy.getIdDocType());
        assertEquals("1234", copy.getIdDocLast4());
        assertEquals("VERIFIED", copy.getKycStatus());
        assertNull(copy.getKycDecidedAt());
        assertNull(copy.getKycNote());
        assertEquals("2020-01-01", copy.getCreatedAt());
        assertEquals("teller1", copy.getRegisteredBy());
        assertNull(copy.getKycDecidedBy());
    }
}
