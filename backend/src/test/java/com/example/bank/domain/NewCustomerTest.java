package com.example.bank.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** 新規顧客の入力：既定は個人、法人も指定できる。 */
class NewCustomerTest {
    @Test
    void defaultsToIndividual() {
        NewCustomer c = new NewCustomer("山田 太郎", "ヤマダ タロウ", LocalDate.of(1980, 1, 1), "100-0001", "東京都",
                "03-0000-0000", "DRIVER_LICENSE", "123456789012");
        assertEquals("INDIVIDUAL", c.customerType);
        assertEquals("山田 太郎", c.name);
        assertEquals("ヤマダ タロウ", c.nameKana);
        assertEquals(LocalDate.of(1980, 1, 1), c.birthDate);
        assertEquals("100-0001", c.postalCode);
        assertEquals("東京都", c.address);
        assertEquals("03-0000-0000", c.phone);
        assertEquals("DRIVER_LICENSE", c.idDocType);
        assertEquals("123456789012", c.idDocNumber);
    }

    @Test
    void corporateCanBeSpecified() {
        assertEquals("CORPORATE", new NewCustomer("株式会社", "カブシキガイシャ", LocalDate.of(2000, 4, 1), null, null,
                null, "CORPORATE_REGISTRY", "1234567890123", "CORPORATE").customerType);
    }
}
