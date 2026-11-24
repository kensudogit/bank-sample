package com.example.bank.support;

import org.junit.jupiter.api.Test;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** ゲートウェイ迂回の防止：Kong の共有シークレットがない要求は勘定系に届かない。 */
class GatewayGuardFilterTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private static HttpServletRequest request(String uri, String secret) {
        Map<String, String> headers = new HashMap<>();
        if (secret != null) headers.put(GatewayGuardFilter.HEADER, secret);
        return (HttpServletRequest) Proxy.newProxyInstance(GatewayGuardFilterTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "getRequestURI": return uri;
                        case "getContextPath": return "/bank";
                        case "getHeader": return headers.get((String) a[0]);
                        case "getMethod": return "POST";
                        case "getRemoteAddr": return "10.0.0.9";
                        default: return null;
                    }
                });
    }

    private static int run(GatewayGuardFilter f, HttpServletRequest req, AtomicBoolean passed) throws Exception {
        AtomicInteger status = new AtomicInteger(200);
        StringWriter body = new StringWriter();
        HttpServletResponse res = (HttpServletResponse) Proxy.newProxyInstance(GatewayGuardFilterTest.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class}, (p, m, a) -> {
                    if ("setStatus".equals(m.getName())) status.set((Integer) a[0]);
                    if ("getWriter".equals(m.getName())) return new PrintWriter(body);
                    return null;
                });
        FilterChain chain = (rq, rs) -> passed.set(true);
        f.doFilter(req, res, chain);
        return status.get();
    }

    @Test
    void requestsWithoutTheGatewaySecretAreRejected() throws Exception {
        GatewayGuardFilter f = new GatewayGuardFilter();
        f.configure(SECRET);
        AtomicBoolean passed = new AtomicBoolean();
        assertEquals(403, run(f, request("/bank/api/accounts", null), passed));
        assertEquals(403, run(f, request("/bank/api/accounts", "wrong"), passed));
        assertFalse(passed.get());
        assertEquals(200, run(f, request("/bank/api/accounts", SECRET), passed));
        assertTrue(passed.get());
    }

    @Test
    void healthChecksDoNotNeedTheSecret() throws Exception {
        GatewayGuardFilter f = new GatewayGuardFilter();
        f.configure(SECRET);
        AtomicBoolean passed = new AtomicBoolean();
        run(f, request("/bank/api/live", null), passed);
        assertTrue(passed.get());
    }

    @Test
    void missingConfigurationFailsClosed() throws Exception {
        GatewayGuardFilter f = new GatewayGuardFilter();
        f.configure(null);
        AtomicBoolean passed = new AtomicBoolean();
        assertEquals(503, run(f, request("/bank/api/accounts", SECRET), passed));
        f.configure("short");
        assertEquals(503, run(f, request("/bank/api/accounts", "short"), passed));
        assertFalse(passed.get());
        f.configure(GatewayGuardFilter.DISABLED);
        run(f, request("/bank/api/accounts", null), passed);
        assertTrue(passed.get());
    }
}
