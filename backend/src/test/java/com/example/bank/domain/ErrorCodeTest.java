package com.example.bank.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** エラーコード：struts.xml の結果名（HTTP ステータス）と、メッセージのキー。 */
class ErrorCodeTest {
    private static final Set<String> RESULT_NAMES = new HashSet<>(Arrays.asList(
            "badRequest", "unauthorized", "forbidden", "notFound", "methodNotAllowed", "conflict", "unprocessable",
            "systemError"));

    @Test
    void everyCodeMapsToAKnownResult() {
        for (ErrorCode c : ErrorCode.values()) {
            assertTrue(RESULT_NAMES.contains(c.resultName()), c + " -> " + c.resultName());
        }
    }

    @Test
    void representativeStatuses() {
        assertEquals("badRequest", ErrorCode.VALIDATION_ERROR.resultName());
        assertEquals("unauthorized", ErrorCode.AUTH_FAILED.resultName());
        assertEquals("forbidden", ErrorCode.FORBIDDEN.resultName());
        assertEquals("notFound", ErrorCode.ACCOUNT_NOT_FOUND.resultName());
        assertEquals("conflict", ErrorCode.DUPLICATE_REQUEST.resultName());
        assertEquals("unprocessable", ErrorCode.INSUFFICIENT_FUNDS.resultName());
        assertEquals("unprocessable", ErrorCode.INTEGRATION_SAME_PERSON.resultName());
        assertEquals("systemError", ErrorCode.SYSTEM_ERROR.resultName());
    }

    @Test
    void messageKeyIsPrefixedName() {
        assertEquals("error.INSUFFICIENT_FUNDS", ErrorCode.INSUFFICIENT_FUNDS.messageKey());
    }
}
