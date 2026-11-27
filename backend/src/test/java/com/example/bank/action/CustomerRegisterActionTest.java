package com.example.bank.action;

import com.example.bank.domain.Customer;
import com.example.bank.service.CustomerService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 顧客の登録：各項目の検証、電話番号の数字化、受付した行員の記録、法人の指定。 */
class CustomerRegisterActionTest extends ActionTestBase {
    private CustomerRegisterAction register() {
        CustomerRegisterAction a = as(TELLER, new CustomerRegisterAction(new CustomerService(() -> db, clock)));
        a.setName("伊藤 健");
        a.setNameKana("イトウ ケン");
        a.setBirthDate("1990-04-01");
        a.setPostalCode("060-0001");
        a.setAddress("北海道札幌市中央区北1条西1-1");
        a.setPhone("011-123-4567");
        a.setIdDocType("MY_NUMBER_CARD");
        a.setIdDocNumber("AB12345678");
        a.setIdempotencyKey(key());
        return a;
    }

    @Test
    void registersAndRecordsTheTeller() {
        CustomerRegisterAction a = register();
        assertValid(a);
        assertEquals("success", a.execute());
        Customer c = (Customer) data(a);
        assertEquals("teller1", c.getRegisteredBy());
        assertEquals("0111234567", c.getPhone());
        assertEquals("INDIVIDUAL", c.getCustomerType());
    }

    @Test
    void corporateFlag() {
        CustomerRegisterAction a = register();
        a.setCustomerType("CORPORATE");
        a.setName("株式会社テスト");
        a.setNameKana("カブシキガイシャテスト");
        assertValid(a);
        a.execute();
        assertTrue(((Customer) data(a)).isCorporate());
    }

    @Test
    void eachFieldIsValidated() {
        CustomerRegisterAction name = register();
        name.setName(" ");
        assertInvalid(name, "name");
        CustomerRegisterAction kana = register();
        kana.setNameKana("ito ken");
        assertInvalid(kana, "nameKana");
        CustomerRegisterAction birth = register();
        birth.setBirthDate("1990-02-30");
        assertInvalid(birth, "birthDate");
        CustomerRegisterAction postal = register();
        postal.setPostalCode("12345");
        assertInvalid(postal, "postalCode");
        CustomerRegisterAction phone = register();
        phone.setPhone("12");
        assertInvalid(phone, "phone");
        CustomerRegisterAction doc = register();
        doc.setIdDocType("LIBRARY_CARD");
        assertInvalid(doc, "idDocType");
    }

    @Test
    void underageIsAnUnprocessableRequest() {
        CustomerRegisterAction a = register();
        a.setBirthDate("2010-01-01");
        assertValid(a);
        assertEquals("unprocessable", a.execute());
        assertEquals("AGE_RESTRICTION", errorCode(a));
    }
}
