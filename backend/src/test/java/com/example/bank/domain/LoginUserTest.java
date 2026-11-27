package com.example.bank.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** ログイン利用者：役割の判定と、パスワード変更後のコピー。 */
class LoginUserTest {
    @Test
    void roles() {
        LoginUser customer = new LoginUser("yamada", "山田", LoginUser.CUSTOMER, "CU0001", false);
        LoginUser teller = new LoginUser("teller1", "窓口", LoginUser.TELLER, null, false);
        LoginUser supervisor = new LoginUser("supervisor1", "役席", LoginUser.SUPERVISOR, null, false);
        LoginUser integration = new LoginUser("svc-mule", "連携", LoginUser.INTEGRATION, null, false);
        assertTrue(customer.isCustomer());
        assertFalse(customer.isStaff());
        assertTrue(teller.isStaff());
        assertTrue(supervisor.isStaff());
        assertFalse(integration.isStaff());
        assertFalse(integration.isCustomer());
        assertFalse(LoginUser.SYSTEM.isStaff());
        assertEquals("SYSTEM", LoginUser.SYSTEM.getUserId());
    }

    @Test
    void withPasswordChangedClearsTheFlagOnly() {
        LoginUser u = new LoginUser("yamada", "山田", LoginUser.CUSTOMER, "CU0001", true);
        LoginUser changed = u.withPasswordChanged();
        assertTrue(u.isMustChangePassword());
        assertFalse(changed.isMustChangePassword());
        assertEquals("yamada", changed.getUserId());
        assertEquals("山田", changed.getDisplayName());
        assertEquals(LoginUser.CUSTOMER, changed.getRole());
        assertEquals("CU0001", changed.getCustomerId());
    }
}
