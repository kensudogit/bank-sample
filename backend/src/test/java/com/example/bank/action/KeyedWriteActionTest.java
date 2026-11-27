package com.example.bank.action;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 冪等キー付きの更新 Action の基底：キーの検証（前後の空白は除く）と、監査・再送用のキー。 */
class KeyedWriteActionTest extends ActionTestBase {
    static final class Probe extends KeyedWriteAction {
        @Override public String execute() { return ok(getIdempotencyKey()); }
    }

    @Test
    void keyIsRequiredAndTrimmed() {
        Probe missing = new Probe();
        assertInvalid(missing, "idempotencyKey");
        assertNull(missing.getRequestKey());

        Probe p = new Probe();
        String k = key();
        p.setIdempotencyKey("  " + k + "  ");
        assertValid(p);
        assertEquals(k, p.getIdempotencyKey());
        assertEquals(k, p.getRequestKey());
    }

    @Test
    void malformedKeyIsRejected() {
        Probe p = new Probe();
        p.setIdempotencyKey("short");
        assertInvalid(p, "idempotencyKey");
        p = new Probe();
        p.setIdempotencyKey("bad key with spaces 0000000000");
        assertInvalid(p, "idempotencyKey");
    }
}
