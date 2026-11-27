package com.example.bank.action;

import com.example.bank.domain.LoginUser;
import com.example.bank.service.PaymentService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 決済の API：他行宛振込（本人の口座から・銀行コードの形式）、一覧の範囲、被仕向の明細、口座振替・海外送金の入力。 */
class PaymentActionTest extends ActionTestBase {
    private PaymentAction payment(LoginUser user) {
        return as(user, new PaymentAction(new PaymentService(() -> db, clock)));
    }

    private PaymentAction send(LoginUser user, String from, String bankCode) {
        PaymentAction a = payment(user);
        a.setAccountNo(from);
        a.setBankCode(bankCode);
        a.setBranchCode("123");
        a.setDestType("1");
        a.setDestAccountNo("7654321");
        a.setDestNameKana("スズキ イチロウ");
        a.setAmount("50000");
        a.setIdempotencyKey(key());
        return a;
    }

    @Test
    void customerSendsFromOwnAccount() {
        PaymentAction a = send(YAMADA, "1000001", "0005");
        assertEquals("success", a.send());
        assertEquals("ｽｽﾞｷ ｲﾁﾛｳ", ((Map<?, ?>) data(a)).get("destNameKana"));
        PaymentAction others = send(YAMADA, "1000003", "0005");
        assertEquals("notFound", others.send());
    }

    @Test
    void sendValidation() {
        assertEquals("badRequest", send(TELLER, "1000001", "12").send());
        PaymentAction badType = send(TELLER, "1000001", "0005");
        badType.setDestType("9");
        assertEquals("badRequest", badType.send());
    }

    @Test
    void listsAreScopedForCustomers() {
        send(TELLER, "1000003", "0005").send();
        PaymentAction yamada = payment(YAMADA);
        assertEquals("success", yamada.remittances());
        assertTrue(((List<?>) data(yamada)).isEmpty());
        PaymentAction teller = payment(TELLER);
        assertEquals("success", teller.remittances());
        assertEquals(1, ((List<?>) data(teller)).size());
        PaymentAction badDirection = payment(TELLER);
        badDirection.setDirection("SIDEWAYS");
        assertEquals("badRequest", badDirection.remittances());
    }

    @Test
    void incomingItemsAreValidated() {
        PaymentAction empty = payment(SUPERVISOR);
        empty.setItems(Collections.<Map<String, Object>>emptyList());
        assertEquals("badRequest", empty.incoming());

        Map<String, Object> item = new HashMap<>();
        item.put("bankCode", "0005");
        item.put("remitterNameKana", "ﾃｽﾄ");
        item.put("accountNo", "1000003");
        item.put("nameKana", "ｻﾄｳ ﾊﾅｺ");
        item.put("amount", "1000");
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item);
        PaymentAction noReference = payment(SUPERVISOR);
        noReference.setItems(items);
        assertEquals("badRequest", noReference.incoming());

        item.put("referenceNo", "ZG-0005-1120-001");
        PaymentAction ok = payment(SUPERVISOR);
        ok.setItems(items);
        assertEquals("success", ok.incoming());
        assertEquals("CREDITED", ((Map<?, ?>) ((List<?>) data(ok)).get(0)).get("status"));
    }

    @Test
    void mandateAndForeignInput() {
        PaymentAction badPayer = payment(TELLER);
        badPayer.setAccountNo("1000004");
        badPayer.setBillerCode("ELEC01");
        badPayer.setPayerNumber("E 0001");
        assertEquals("badRequest", badPayer.mandateCreate());
        PaymentAction mandate = payment(TELLER);
        mandate.setAccountNo("1000004");
        mandate.setBillerCode("ELEC01");
        mandate.setPayerNumber("E-0001");
        assertEquals("success", mandate.mandateCreate());

        PaymentAction badAmount = payment(YAMADA);
        badAmount.setAccountNo("1000002");
        badAmount.setFeeAccountNo("1000001");
        badAmount.setCurrency("USD");
        badAmount.setAmount("1,000");
        badAmount.setIdempotencyKey(key());
        assertEquals("badRequest", badAmount.foreignSend());
        PaymentAction banks = payment(YAMADA);
        assertEquals("success", banks.banks());
    }
}
