package com.example.bank.action;

import com.example.bank.domain.LoginUser;
import com.example.bank.interceptor.ApiGuardInterceptor;
import com.example.bank.service.AuthService;
import com.example.bank.testsupport.FakeHttp;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** ログイン・ログアウト・本人情報・パスワード変更：セッションの作り直し、入力の検証、平文パスワードを残さない。 */
class AuthActionTest extends ActionTestBase {
    private AuthAction auth() {
        return new AuthAction(new AuthService(() -> db, clock));
    }

    @Test
    void loginStoresTheUserInANewSession() {
        Map<String, Object> oldAttrs = new HashMap<>();
        HttpSession old = FakeHttp.session(oldAttrs);
        oldAttrs.put("stale", "x");
        HttpServletRequest request = FakeHttp.request().jsonPost().session(old).build();
        struts.with(request);
        AuthAction a = auth();
        a.setUserId(" Teller1 ");
        a.setPassword("BankDemo#2026");
        assertEquals("success", a.login());
        // ログイン前のセッションは破棄して作り直す（セッション固定攻撃の対策）
        assertTrue(oldAttrs.isEmpty());
        LoginUser user = (LoginUser) data(a);
        assertEquals("teller1", user.getUserId());
        assertSame(user, request.getSession(false).getAttribute(ApiGuardInterceptor.SESSION_USER));
        assertEquals("teller1", a.getAuditUserHint());
    }

    @Test
    void loginInputAndFailure() {
        AuthAction blank = auth();
        blank.setUserId("ab");
        blank.setPassword("x");
        assertEquals("input", blank.login());
        assertTrue(blank.getFieldErrors().containsKey("userId"));

        AuthAction wrong = auth();
        wrong.setUserId("teller1");
        wrong.setPassword("wrong-password-1");
        assertEquals("unauthorized", wrong.login());
        assertEquals("AUTH_FAILED", errorCode(wrong));
    }

    @Test
    void logoutInvalidatesTheSession() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(ApiGuardInterceptor.SESSION_USER, TELLER);
        struts.with(FakeHttp.request().jsonPost().session(FakeHttp.session(attrs)).build());
        AuthAction a = as(TELLER, auth());
        assertEquals("success", a.logout());
        assertTrue(attrs.isEmpty());
        // セッションがなくても失敗しない
        struts.with(FakeHttp.request().jsonPost().build());
        assertEquals("success", auth().logout());
    }

    @Test
    void meReturnsTheCurrentUser() {
        AuthAction a = as(YAMADA, auth());
        assertEquals("success", a.me());
        assertSame(YAMADA, data(a));
    }

    @Test
    void changePassword() {
        HttpServletRequest request = FakeHttp.request().jsonPost().build();
        struts.with(request);
        AuthAction missing = as(YAMADA, auth());
        assertEquals("input", missing.changePassword());

        AuthAction a = as(YAMADA, auth());
        a.setCurrentPassword("BankDemo#2026");
        a.setNewPassword("NewPassword2026");
        assertEquals("success", a.changePassword());
        LoginUser updated = (LoginUser) data(a);
        assertFalse(updated.isMustChangePassword());
        assertSame(updated, request.getSession(false).getAttribute(ApiGuardInterceptor.SESSION_USER));

        AuthAction weak = as(YAMADA, auth());
        weak.setCurrentPassword("NewPassword2026");
        weak.setNewPassword("short");
        assertEquals("badRequest", weak.changePassword());
        assertEquals("PASSWORD_POLICY", errorCode(weak));
    }
}
