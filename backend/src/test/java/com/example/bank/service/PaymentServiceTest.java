package com.example.bank.service;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 決済：振込手数料、他行宛振込と全銀データ、被仕向の名義照合、口座振替の契約、海外送金の照合。 */
class PaymentServiceTest {
    private TestDatabase db;
    private TestClock clock;
    private BankService bank;
    private PaymentService payments;

    @BeforeEach
    void setUp() {
        db = TestDatabase.create();
        clock = new TestClock(LocalDateTime.of(2026, 11, 20, 10, 0));
        bank = new BankService(() -> db, clock);
        payments = new PaymentService(() -> db, clock);
    }

    private static String key() { return UUID.randomUUID().toString(); }

    private static BigDecimal yen(long v) { return BigDecimal.valueOf(v); }

    private static void assertError(ErrorCode expected, Executable call) {
        assertEquals(expected, assertThrows(BankException.class, call).getCode());
    }

    @Test
    void domesticFeeHasTwoBands() {
        assertEquals(new BigDecimal("330"), PaymentService.domesticFee(yen(29_999)));
        assertEquals(new BigDecimal("440"), PaymentService.domesticFee(yen(30_000)));
    }

    @Test
    void sendDomesticDebitsAmountAndFee() {
        assertFalse(payments.banks().isEmpty());
        Map<String, Object> r = payments.sendDomestic("1000001", "0005", "123", "1", "7654321", "スズキ イチロウ",
                yen(50_000), key(), "yamada");
        assertEquals("ｽｽﾞｷ ｲﾁﾛｳ", r.get("destNameKana"));
        assertEquals("2026-11-20", r.get("valueDate"));
        assertEquals(0, yen(1_250_000 - 50_440).compareTo(bank.getAccount("1000001").getBalance()));
        assertError(ErrorCode.BANK_NOT_FOUND, () -> payments.sendDomestic("1000001", "1234", "001", "1", "1111111", "タナカ",
                yen(1_000), key(), "yamada"));
    }

    @Test
    void afterTheCutOffTheValueDateIsTheNextBusinessDay() {
        clock.set(LocalDateTime.of(2026, 11, 20, 15, 30));
        Map<String, Object> r = payments.sendDomestic("1000001", "0009", "200", "1", "2222222", "ヤマダ", yen(5_000), key(), "yamada");
        assertEquals("2026-11-24", r.get("valueDate"));
    }

    @Test
    void zenginBatchHas120CharacterRecords() {
        payments.sendDomestic("1000001", "0005", "123", "1", "7654321", "スズキ イチロウ", yen(50_000), key(), "yamada");
        Map<String, Object> batch = payments.createZenginBatch("supervisor1");
        assertEquals(1, ((Number) batch.get("recordCount")).intValue());
        for (String line : ((String) batch.get("content")).split("\r\n")) assertEquals(120, line.length());
        assertFalse(payments.zenginBatches().isEmpty());
    }

    @Test
    void incomingIsCreditedOnlyWhenTheNameMatches() {
        PaymentService.Incoming ok = incoming("ｻﾄｳ ﾊﾅｺ", 120_000);
        PaymentService.Incoming wrong = incoming("ｻﾄｳ ﾀﾛｳ", 30_000);
        List<Map<String, Object>> res = payments.receiveIncoming(Arrays.asList(ok, wrong), "supervisor1");
        assertEquals("CREDITED", res.get(0).get("status"));
        assertEquals("HELD", res.get(1).get("status"));
        assertEquals(0, yen(600_000).compareTo(bank.getAccount("1000003").getBalance()));
    }

    private static PaymentService.Incoming incoming(String nameKana, long amount) {
        PaymentService.Incoming i = new PaymentService.Incoming();
        i.bankCode = "0005";
        i.remitterNameKana = "ﾃｽﾄ";
        i.accountNo = "1000003";
        i.nameKana = nameKana;
        i.amount = yen(amount);
        return i;
    }

    @Test
    void mandateIsUniquePerBillerAndPayer() {
        Map<String, Object> m = payments.registerMandate("1000004", "ELEC01", "E-0001", "teller1");
        long id = new BigDecimal(m.get("mandateId").toString()).longValue();
        assertEquals("CU0003", payments.mandateOwner(id));
        assertError(ErrorCode.DUPLICATE_REQUEST, () -> payments.registerMandate("1000003", "ELEC01", "E-0001", "teller1"));
        assertFalse(payments.billers().isEmpty());
    }

    @Test
    void zenginFileLinesAreFixedLength() {
        String file = PaymentService.zenginFile(java.util.Collections.<Map<String, Object>>emptyList(), LocalDate.of(2026, 11, 20));
        for (String line : file.split("\r\n")) assertEquals(120, line.length());
    }

    @Test
    void foreignRemittanceToAHighRiskCountryIsHeld() {
        PaymentService.ForeignOrder o = new PaymentService.ForeignOrder();
        o.accountNo = "1000002";
        o.feeAccountNo = "1000001";
        o.currency = "USD";
        o.amount = "1000.00";
        o.beneficiaryName = "Demo Trading";
        o.beneficiaryAccount = "123456789";
        o.swiftBic = "CHASUS33";
        o.country = "KP";
        o.purpose = "Trade";
        Map<String, Object> held = payments.sendForeign(o, key(), "yamada");
        assertEquals("HELD", held.get("status"));
        long id = new BigDecimal(held.get("remitId").toString()).longValue();
        payments.decideForeign(id, false, "謝絶", "supervisor1");
        assertEquals(0, yen(3_000_000).compareTo(bank.getAccount("1000002").getBalance()));
    }
}
