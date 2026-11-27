package com.example.bank.interceptor;

import com.example.bank.action.BaseApiAction;
import com.example.bank.domain.LoginUser;
import com.example.bank.testsupport.FakeHttp;
import com.example.bank.testsupport.StrutsTestSupport;
import com.opensymphony.xwork2.ActionInvocation;
import com.opensymphony.xwork2.ActionProxy;
import com.opensymphony.xwork2.interceptor.PreResultListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * API の関所：メソッド・CSRF・ログイン・初期パスワード・ロール・サービスアカウント・Agent の判定。
 * 拒否した場合は Action を実行しない。監査ログの書き込み（JNDI の DB）はテストでは失敗するが、業務の応答は止めない設計。
 */
class ApiGuardInterceptorTest {
    private static final LoginUser TELLER = new LoginUser("teller1", "窓口", LoginUser.TELLER, null, false);
    private static final LoginUser SUPERVISOR = new LoginUser("supervisor1", "役席", LoginUser.SUPERVISOR, null, false);
    private static final LoginUser CUSTOMER = new LoginUser("yamada", "山田", LoginUser.CUSTOMER, "CU0001", false);
    private static final LoginUser SERVICE = new LoginUser("svc-mule", "連携", LoginUser.INTEGRATION, null, false);

    private StrutsTestSupport struts;
    private TestAction action;
    private int invoked;

    /** 実行されたら ok を返し、渡された利用者を覚えておく Action。 */
    static final class TestAction extends BaseApiAction {
        LoginUser seen;

        TestAction() { super(null); }

        @Override
        public String execute() {
            seen = currentUser();
            return ok("done");
        }
    }

    @BeforeEach
    void setUp() {
        struts = StrutsTestSupport.start();
        action = new TestAction();
        invoked = 0;
    }

    @AfterEach
    void tearDown() {
        struts.stop();
    }

    private static HttpSession sessionWith(LoginUser user) {
        Map<String, Object> attrs = new HashMap<>();
        if (user != null) attrs.put(ApiGuardInterceptor.SESSION_USER, user);
        return FakeHttp.session(attrs);
    }

    private ActionInvocation invocation() {
        List<PreResultListener> listeners = new ArrayList<>();
        String[] resultCode = {null};
        ActionProxy proxy = FakeHttp.proxy(ActionProxy.class, (m, args) ->
                "getActionName".equals(m.getName()) ? "test-action" : FakeHttp.defaultValue(m));
        ActionInvocation[] self = new ActionInvocation[1];
        self[0] = FakeHttp.proxy(ActionInvocation.class, (m, args) -> {
            switch (m.getName()) {
                case "getAction": return action;
                case "getProxy": return proxy;
                case "addPreResultListener": listeners.add((PreResultListener) args[0]); return null;
                case "getResultCode": return resultCode[0];
                case "setResultCode": resultCode[0] = (String) args[0]; return null;
                case "invoke":
                    invoked++;
                    String r = action.execute();
                    resultCode[0] = r;
                    for (PreResultListener l : listeners) l.beforeResult(self[0], r);
                    return r;
                default: return FakeHttp.defaultValue(m);
            }
        });
        return self[0];
    }

    private String run(ApiGuardInterceptor guard, HttpServletRequest request) throws Exception {
        struts.with(request);
        return guard.intercept(invocation());
    }

    private static ApiGuardInterceptor guard(String methods, String roles) {
        ApiGuardInterceptor g = new ApiGuardInterceptor();
        if (methods != null) g.setAllowedMethods(methods);
        if (roles != null) g.setRoles(roles);
        return g;
    }

    private String errorCode() {
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) action.getResponse().get("error");
        return error == null ? null : (String) error.get("code");
    }

    @Test
    void loggedInUserCanCallAuthenticatedGet() throws Exception {
        String r = run(guard(null, null), FakeHttp.request().session(sessionWith(TELLER)).build());
        assertEquals("success", r);
        assertEquals(1, invoked);
        assertSame(TELLER, action.seen);
        assertEquals("no-store", struts.responseHeaders().get("Cache-Control"));
        assertEquals("nosniff", struts.responseHeaders().get("X-Content-Type-Options"));
    }

    @Test
    void methodNotAllowedListsAllowedMethods() throws Exception {
        String r = run(guard("GET", null), FakeHttp.request().jsonPost().session(sessionWith(TELLER)).build());
        assertEquals("methodNotAllowed", r);
        assertEquals("METHOD_NOT_ALLOWED", errorCode());
        assertEquals("GET", struts.responseHeaders().get("Allow"));
        assertEquals(0, invoked);
    }

    @Test
    void writesNeedJsonAndXmlHttpRequest() throws Exception {
        ApiGuardInterceptor g = guard("POST", null);
        assertEquals("forbidden", run(g, FakeHttp.request().method("POST").contentType("application/json")
                .session(sessionWith(TELLER)).build()));
        assertEquals("forbidden", run(g, FakeHttp.request().method("POST").contentType("application/x-www-form-urlencoded")
                .header("X-Requested-With", "XMLHttpRequest").session(sessionWith(TELLER)).build()));
        assertEquals(0, invoked);
    }

    @Test
    void anonymousIsUnauthenticatedUnlessPublic() throws Exception {
        assertEquals("unauthorized", run(guard(null, null), FakeHttp.request().build()));
        assertEquals("UNAUTHENTICATED", errorCode());
        assertEquals(0, invoked);
        action = new TestAction();
        assertEquals("success", run(guard(null, "PUBLIC"), FakeHttp.request().build()));
        assertNull(action.seen);
    }

    @Test
    void initialPasswordBlocksBusinessApisButNotPasswordChange() throws Exception {
        LoginUser mustChange = new LoginUser("yamada", "山田", LoginUser.CUSTOMER, "CU0001", true);
        assertEquals("forbidden", run(guard(null, null), FakeHttp.request().session(sessionWith(mustChange)).build()));
        assertEquals("PASSWORD_CHANGE_REQUIRED", errorCode());
        action = new TestAction();
        ApiGuardInterceptor passwordApi = guard(null, null);
        passwordApi.setAllowDuringPasswordChange("true");
        assertEquals("success", run(passwordApi, FakeHttp.request().session(sessionWith(mustChange)).build()));
    }

    @Test
    void rolesAreCheckedCaseInsensitively() throws Exception {
        ApiGuardInterceptor supervisorsOnly = guard(null, "supervisor");
        assertEquals("forbidden", run(supervisorsOnly, FakeHttp.request().session(sessionWith(TELLER)).build()));
        assertEquals("FORBIDDEN", errorCode());
        assertEquals(0, invoked);
        action = new TestAction();
        assertEquals("success", run(supervisorsOnly, FakeHttp.request().session(sessionWith(SUPERVISOR)).build()));
    }

    @Test
    void serviceAccountNeedsItsRoleListedExplicitly() throws Exception {
        // AUTHENTICATED の API でも、連携のサービスアカウントは使えない
        assertEquals("forbidden", run(guard(null, "AUTHENTICATED"), FakeHttp.request().session(sessionWith(SERVICE)).build()));
        action = new TestAction();
        assertEquals("success", run(guard(null, "INTEGRATION"), FakeHttp.request().session(sessionWith(SERVICE)).build()));
        action = new TestAction();
        assertEquals("success", run(guard(null, "AUTHENTICATED"), FakeHttp.request().session(sessionWith(CUSTOMER)).build()));
    }

    @Test
    void appTokenNeedsMeterRoleAndValidAgentId() throws Exception {
        ApiGuardInterceptor meter = guard(null, "AGENT");
        HttpServletRequest ok = FakeHttp.request().header("X-Bank-Auth", "app-token").header("X-Bank-Agent-Id", "agent-app")
                .header("X-Bank-Roles", "Agent.Meter").build();
        assertEquals("success", run(meter, ok));
        assertEquals("agent:agent-app", action.seen.getUserId());
        assertEquals(LoginUser.AGENT, action.seen.getRole());

        action = new TestAction();
        HttpServletRequest noRole = FakeHttp.request().header("X-Bank-Auth", "app-token").header("X-Bank-Agent-Id", "agent-app")
                .header("X-Bank-Roles", "Other").build();
        assertEquals("unauthorized", run(meter, noRole));
        action = new TestAction();
        HttpServletRequest badId = FakeHttp.request().header("X-Bank-Auth", "app-token").header("X-Bank-Agent-Id", "bad id!")
                .header("X-Bank-Roles", "Agent.Meter").build();
        assertEquals("unauthorized", run(meter, badId));
    }

    @Test
    void delegatedTokenWithoutValidUserIsRejectedEvenWithASession() throws Exception {
        ApiGuardInterceptor g = guard(null, null);
        g.setAgentScope("Accounts.Read");
        // 委任トークンの要求では、セッションの利用者は使わない
        HttpServletRequest r = FakeHttp.request().header("X-Bank-Auth", "delegated-token").header("X-Bank-Scopes", "Accounts.Read")
                .header("X-Bank-Delegated-User", "not valid!").session(sessionWith(SUPERVISOR)).build();
        assertEquals("unauthorized", run(g, r));
        assertEquals(0, invoked);
    }

    @Test
    void sessionUserHandlesMissingAndInvalidatedSessions() {
        assertNull(ApiGuardInterceptor.sessionUser(FakeHttp.request().build()));
        HttpSession s = sessionWith(TELLER);
        assertSame(TELLER, ApiGuardInterceptor.sessionUser(FakeHttp.request().session(s).build()));
        s.invalidate();
        assertNull(ApiGuardInterceptor.sessionUser(FakeHttp.request().session(s).build()));
    }

    @Test
    void writeWithoutRequestKeyRunsAndIsAudited() throws Exception {
        // 監査ログの書き込みに失敗しても（テストでは JNDI がない）、業務の結果はそのまま返す
        String r = run(guard("POST", null), FakeHttp.request().jsonPost().session(sessionWith(TELLER)).build());
        assertEquals("success", r);
        assertEquals(1, invoked);
    }
}
