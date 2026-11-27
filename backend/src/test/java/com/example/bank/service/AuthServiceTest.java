package com.example.bank.service;

import com.example.bank.domain.BankException;
import com.example.bank.domain.Customer;
import com.example.bank.domain.ErrorCode;
import com.example.bank.domain.LoginUser;
import com.example.bank.domain.NewCustomer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 認証：ログイン、失敗の連続によるロック、パスワード変更とポリシー、顧客ログインの発行、Agent の委任先の行員。 */
class AuthServiceTest {
    private static final String DEMO_PASSWORD = "BankDemo#2026";
    private TestDatabase db;
    private TestClock clock;
    private AuthService auth;

    @BeforeEach
    void setUp() {
        db = TestDatabase.create();
        clock = new TestClock(LocalDateTime.of(2026, 11, 20, 10, 0));
        auth = new AuthService(() -> db, clock);
    }

    private static void assertError(ErrorCode expected, Executable call) {
        assertEquals(expected, assertThrows(BankException.class, call).getCode());
    }

    @Test
    void loginReturnsRoleAndCustomer() {
        LoginUser yamada = auth.login("yamada", DEMO_PASSWORD);
        assertEquals(LoginUser.CUSTOMER, yamada.getRole());
        assertEquals("CU0001", yamada.getCustomerId());
        assertEquals(LoginUser.SUPERVISOR, auth.login("supervisor1", DEMO_PASSWORD).getRole());
    }

    @Test
    void wrongPasswordAndUnknownUserFailTheSameWay() {
        assertError(ErrorCode.AUTH_FAILED, () -> auth.login("yamada", "wrong-password-1"));
        assertError(ErrorCode.AUTH_FAILED, () -> auth.login("nobody", "wrong-password-1"));
    }

    @Test
    void repeatedFailuresLockTheAccountTemporarily() {
        for (int i = 0; i < 4; i++) assertError(ErrorCode.AUTH_FAILED, () -> auth.login("sato", "wrong-password-1"));
        assertError(ErrorCode.ACCOUNT_LOCKED, () -> auth.login("sato", "wrong-password-1"));
        // ロック中は正しいパスワードでも入れない
        assertError(ErrorCode.ACCOUNT_LOCKED, () -> auth.login("sato", DEMO_PASSWORD));
        clock.set(LocalDateTime.of(2026, 11, 20, 11, 0));
        assertEquals("sato", auth.login("sato", DEMO_PASSWORD).getUserId());
    }

    @Test
    void changePasswordChecksCurrentPasswordAndPolicy() {
        LoginUser u = auth.login("suzuki", DEMO_PASSWORD);
        assertError(ErrorCode.PASSWORD_POLICY, () -> auth.changePassword(u, DEMO_PASSWORD, "short1"));
        assertError(ErrorCode.AUTH_FAILED, () -> auth.changePassword(u, "not-the-password1", "NewPassword2026"));
        assertFalse(auth.changePassword(u, DEMO_PASSWORD, "NewPassword2026").isMustChangePassword());
        assertError(ErrorCode.AUTH_FAILED, () -> auth.login("suzuki", DEMO_PASSWORD));
        assertEquals("suzuki", auth.login("suzuki", "NewPassword2026").getUserId());
    }

    @Test
    void customerLoginNeedsVerifiedKycAndIsIssuedOnce() {
        CustomerService customers = new CustomerService(() -> db, clock);
        Customer c = customers.register(new NewCustomer("伊藤 健", "イトウ ケン", LocalDate.of(1990, 4, 1), "060-0001",
                "北海道札幌市中央区北1条西1-1", "0111234567", "MY_NUMBER_CARD", "AB12345678"), UUID.randomUUID().toString(), "teller1");
        assertError(ErrorCode.KYC_NOT_VERIFIED, () -> auth.issueCustomerLogin(c.getCustomerId()));
        customers.decideKyc(c.getCustomerId(), "VERIFIED", "確認", "supervisor1");
        Map<String, String> issued = auth.issueCustomerLogin(c.getCustomerId());
        LoginUser first = auth.login(issued.get("userId"), issued.get("initialPassword"));
        assertTrue(first.isMustChangePassword());
        assertError(ErrorCode.LOGIN_ALREADY_ISSUED, () -> auth.issueCustomerLogin(c.getCustomerId()));
        assertError(ErrorCode.CUSTOMER_NOT_FOUND, () -> auth.issueCustomerLogin("CU9999"));
    }

    @Test
    void delegatedStaffIsOnlyForActiveStaff() {
        LoginUser teller = auth.delegatedStaff("teller1");
        assertNotNull(teller);
        assertEquals(LoginUser.TELLER, teller.getRole());
        assertNull(auth.delegatedStaff("yamada"));      // 顧客は委任先にできない
        assertNull(auth.delegatedStaff("nobody"));
        assertNull(auth.delegatedStaff("bad id!"));
        assertNull(auth.delegatedStaff(null));
    }
}
