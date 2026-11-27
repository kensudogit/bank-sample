package com.example.bank.action;

import com.example.bank.service.BankService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** 入出金・振込の基底：金額（1 回の上限）、摘要、冪等キーの検証。 */
class MoneyOperationActionTest extends ActionTestBase {
    static final class Probe extends MoneyOperationAction {
        Probe() { super(null); }

        @Override public String execute() { return ok(amountValue()); }
    }

    private static Probe valid() {
        Probe p = new Probe();
        p.setAmount("1000");
        p.setMemo("  ");
        p.setIdempotencyKey(key());
        return p;
    }

    @Test
    void validInput() {
        Probe p = valid();
        assertValid(p);
        assertNull(p.getMemo());
        p.execute();
        assertEquals(0, new BigDecimal("1000").compareTo((BigDecimal) data(p)));
        assertNotNull(p.getRequestKey());
    }

    @Test
    void amountMustBePositiveAndWithinTheLimit() {
        Probe zero = valid();
        zero.setAmount("0");
        assertInvalid(zero, "amount");
        Probe text = valid();
        text.setAmount("abc");
        assertInvalid(text, "amount");
        Probe over = valid();
        over.setAmount(BankService.MAX_AMOUNT_PER_OPERATION.add(BigDecimal.ONE).toPlainString());
        assertInvalid(over, "amount");
    }

    @Test
    void memoAndKeyAreChecked() {
        Probe memo = valid();
        StringBuilder longMemo = new StringBuilder();
        for (int i = 0; i < 200; i++) longMemo.append('あ');
        memo.setMemo(longMemo.toString());
        assertInvalid(memo, "memo");
        Probe noKey = valid();
        noKey.setIdempotencyKey(null);
        assertInvalid(noKey, "idempotencyKey");
        assertNull(noKey.getRequestKey());
    }
}
