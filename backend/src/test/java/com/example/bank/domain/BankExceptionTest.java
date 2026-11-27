package com.example.bank.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 業務例外：コードがメッセージになり、引数は防御的にコピーされる。 */
class BankExceptionTest {
    @Test
    void messageIsTheCodeName() {
        BankException e = new BankException(ErrorCode.ACCOUNT_NOT_FOUND, "1000001");
        assertEquals("ACCOUNT_NOT_FOUND", e.getMessage());
        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND, e.getCode());
        assertArrayEquals(new String[]{"1000001"}, e.getArgs());
    }

    @Test
    void argsAreCopiedOnTheWayInAndOut() {
        String[] args = {"a"};
        BankException e = new BankException(ErrorCode.VALIDATION_ERROR, args);
        args[0] = "changed";
        assertEquals("a", e.getArgs()[0]);
        e.getArgs()[0] = "changed";
        assertEquals("a", e.getArgs()[0]);
    }

    @Test
    void nullArgsBecomeEmpty() {
        assertEquals(0, new BankException(ErrorCode.SYSTEM_ERROR, (String[]) null).getArgs().length);
        assertEquals(0, new BankException(ErrorCode.SYSTEM_ERROR).getArgs().length);
    }
}
