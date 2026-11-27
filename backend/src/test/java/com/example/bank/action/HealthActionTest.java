package com.example.bank.action;

import com.example.bank.service.BankService;
import com.example.bank.testsupport.FakeHttp;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 死活確認：DB に届けば UP、届かなければ DOWN（unavailable）。live は DB に依存しない。 */
class HealthActionTest extends ActionTestBase {
    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(HealthAction a) {
        return (Map<String, Object>) a.getResponse().get("data");
    }

    @Test
    void upWhenTheDatabaseAnswers() {
        HealthAction a = new HealthAction(new BankService(() -> db, clock));
        assertEquals("success", a.execute());
        assertEquals("UP", body(a).get("status"));
        assertEquals("UP", body(a).get("database"));
        assertNotNull(body(a).get("instance"));
    }

    @Test
    void downWhenTheDatabaseIsUnreachable() {
        DataSource broken = FakeHttp.proxy(DataSource.class, (m, args) -> {
            throw new SQLException("connection refused", "42000");
        });
        HealthAction a = new HealthAction(new BankService(() -> broken, clock));
        assertEquals("unavailable", a.execute());
        assertEquals("DOWN", body(a).get("status"));
    }

    @Test
    void liveDoesNotTouchTheDatabase() {
        HealthAction a = new HealthAction(null);
        assertEquals("success", a.live());
        assertEquals("UP", body(a).get("status"));
        assertTrue(((Number) body(a).get("uptimeSeconds")).longValue() >= 0);
    }
}
