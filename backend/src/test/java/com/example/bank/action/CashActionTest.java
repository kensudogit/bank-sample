package com.example.bank.action;

import com.example.bank.domain.OperationResult;
import com.example.bank.service.BankService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** 窓口の入金・出金：口座番号の検証、成功時の結果、業務エラー（残高不足・凍結）の応答。 */
class CashActionTest extends ActionTestBase {
    private CashAction cash(String accountNo, String amount) {
        CashAction a = as(TELLER, new CashAction(new BankService(() -> db, clock)));
        a.setAccountNo(accountNo);
        a.setAmount(amount);
        a.setIdempotencyKey(key());
        return a;
    }

    @Test
    void depositAndWithdraw() {
        CashAction in = cash("1000004", "5000");
        assertValid(in);
        assertEquals("success", in.deposit());
        assertEquals(0, new BigDecimal("125000").compareTo(((OperationResult) data(in)).getBalanceAfter()));
        assertEquals("1000004 5000", in.getAuditTarget());

        CashAction out = cash("1000004", "25000");
        assertValid(out);
        assertEquals("success", out.withdraw());
        assertEquals(0, new BigDecimal("100000").compareTo(((OperationResult) data(out)).getBalanceAfter()));
    }

    @Test
    void invalidAccountNumber() {
        assertInvalid(cash("12345", "100"), "accountNo");
    }

    @Test
    void businessErrorsBecomeErrorResponses() {
        CashAction tooMuch = cash("1000004", "999999");
        assertValid(tooMuch);
        assertEquals("unprocessable", tooMuch.withdraw());
        assertEquals("INSUFFICIENT_FUNDS", errorCode(tooMuch));

        CashAction frozen = cash("1000005", "100");
        assertValid(frozen);
        assertEquals("unprocessable", frozen.deposit());
        assertEquals("ACCOUNT_NOT_ACTIVE", errorCode(frozen));
    }
}
