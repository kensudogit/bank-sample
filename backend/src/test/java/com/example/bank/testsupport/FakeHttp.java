package com.example.bank.testsupport;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * テスト用の最小限の HTTP 要求・応答・セッション（java.lang.reflect.Proxy で作る。モックのライブラリは使わない）。
 * 使わないメソッドは null / false / 0 を返す。
 */
public final class FakeHttp {
    private FakeHttp() {}

    /** 要求の組み立て。 */
    public static final class Request {
        private String method = "GET";
        private String contentType;
        private String remoteAddr = "127.0.0.1";
        private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private final Map<String, String> params = new HashMap<>();
        private HttpSession session;
        private String body = "";

        public Request method(String m) { this.method = m; return this; }
        public Request body(String b) { this.body = b; return this; }
        public Request contentType(String ct) { this.contentType = ct; return this; }
        public Request header(String name, String value) { headers.put(name, value); return this; }
        public Request param(String name, String value) { params.put(name, value); return this; }
        public Request session(HttpSession s) { this.session = s; return this; }
        public Request remoteAddr(String ip) { this.remoteAddr = ip; return this; }

        /** 更新系の要求（JSON・XMLHttpRequest 付き。CSRF の確認を通る形）。 */
        public Request jsonPost() {
            return method("POST").contentType("application/json; charset=UTF-8").header("X-Requested-With", "XMLHttpRequest");
        }

        public HttpServletRequest build() {
            return proxy(HttpServletRequest.class, (m, args) -> {
                switch (m.getName()) {
                    case "getMethod": return method;
                    case "getContentType": return contentType;
                    case "getHeader": return headers.get((String) args[0]);
                    case "getParameter": return params.get((String) args[0]);
                    case "getRemoteAddr": return remoteAddr;
                    case "getSession":
                        // サーブレットと同じく、無効化したセッションは返さない（getSession(true) なら新しく作る）
                        if (session != null && INVALIDATED.containsKey(session)) session = null;
                        if (args != null && args.length == 1 && Boolean.FALSE.equals(args[0])) return session;
                        if (session == null) session = FakeHttp.session(new HashMap<String, Object>());
                        return session;
                    case "getRequestURI": return "/bank/api/test";
                    case "getContextPath": return "/bank";
                    case "getHeaderNames": return Collections.enumeration(headers.keySet());
                    case "getReader": return new java.io.BufferedReader(new java.io.StringReader(body));
                    default: return defaultValue(m);
                }
            });
        }
    }

    /** @return 要求の組み立てを始める */
    public static Request request() { return new Request(); }

    /** 無効化したセッション（要求の getSession がこれを見て新しいセッションを作る）。 */
    private static final Map<HttpSession, Boolean> INVALIDATED = Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** 属性を attributes に保持するセッション。invalidate 後の getAttribute・setAttribute は IllegalStateException。 */
    public static HttpSession session(Map<String, Object> attributes) {
        HttpSession[] self = new HttpSession[1];
        self[0] = proxy(HttpSession.class, (m, args) -> {
            boolean invalid = INVALIDATED.containsKey(self[0]);
            switch (m.getName()) {
                case "getAttribute":
                    if (invalid) throw new IllegalStateException("invalidated");
                    return attributes.get((String) args[0]);
                case "setAttribute":
                    if (invalid) throw new IllegalStateException("invalidated");
                    attributes.put((String) args[0], args[1]);
                    return null;
                case "removeAttribute": attributes.remove((String) args[0]); return null;
                case "invalidate": INVALIDATED.put(self[0], Boolean.TRUE); attributes.clear(); return null;
                case "getId": return "test-session";
                default: return defaultValue(m);
            }
        });
        return self[0];
    }

    /** ヘッダーとステータスを headers に記録する応答（ステータスはキー ":status"）。 */
    public static HttpServletResponse response(Map<String, String> headers) {
        return proxy(HttpServletResponse.class, (m, args) -> {
            switch (m.getName()) {
                case "setHeader":
                case "addHeader": headers.put((String) args[0], (String) args[1]); return null;
                case "getHeader": return headers.get((String) args[0]);
                case "setStatus": headers.put(":status", String.valueOf(args[0])); return null;
                default: return defaultValue(m);
            }
        });
    }

    /** 呼ばれたメソッドに応じた値を返す処理。 */
    public interface Handler {
        Object handle(Method m, Object[] args) throws Throwable;
    }

    /** インターフェースのプロキシを作る（Object のメソッドも扱う）。 */
    @SuppressWarnings("unchecked")
    public static <T> T proxy(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(FakeHttp.class.getClassLoader(), new Class<?>[]{type}, (p, m, args) -> {
            if (m.getDeclaringClass() == Object.class) {
                switch (m.getName()) {
                    case "equals": return p == args[0];
                    case "hashCode": return System.identityHashCode(p);
                    default: return type.getSimpleName() + "(fake)";
                }
            }
            return handler.handle(m, args);
        });
    }

    /** 戻り値の型の既定値（参照型は null、boolean は false、数値は 0）。 */
    public static Object defaultValue(Method m) {
        Class<?> r = m.getReturnType();
        if (!r.isPrimitive() || r == void.class) return null;
        if (r == boolean.class) return false;
        if (r == long.class) return 0L;
        if (r == double.class) return 0d;
        if (r == float.class) return 0f;
        if (r == char.class) return '\0';
        if (r == byte.class) return (byte) 0;
        if (r == short.class) return (short) 0;
        return 0;
    }
}
