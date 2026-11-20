package com.example.bank.interceptor;

import com.example.bank.action.BaseApiAction;
import com.example.bank.domain.ErrorCode;
import com.example.bank.domain.LoginUser;
import com.example.bank.service.AuditService;
import com.example.bank.service.IdempotencyService;
import com.opensymphony.xwork2.ActionInvocation;
import com.opensymphony.xwork2.interceptor.AbstractInterceptor;
import org.apache.struts2.ServletActionContext;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * API 共通ガード。
 * <ul>
 *   <li>許可 HTTP メソッド以外は 405</li>
 *   <li>更新系（GET 以外）は Content-Type: application/json と X-Requested-With: XMLHttpRequest を必須とする
 *       （クロスサイトの単純フォーム送信では付与できないため CSRF 対策になる）</li>
 *   <li>認証・認可: roles に PUBLIC（ログイン不要）、AUTHENTICATED（ログイン済みなら誰でも）、または役割名を列挙。
 *       既定は AUTHENTICATED（設定漏れで公開されないようにする）。未ログインは 401、権限なしは 403</li>
 *   <li>初期パスワードのままの利用者は、パスワード変更などの許可された API 以外は 403</li>
 *   <li>監査ログ: 更新系と audit=true の API、および権限エラーを記録する</li>
 *   <li>セキュリティ系レスポンスヘッダーを付与</li>
 * </ul>
 */
public class ApiGuardInterceptor extends AbstractInterceptor {
    public static final String SESSION_USER = "bank.loginUser";
    private static final String PUBLIC = "PUBLIC";
    private static final String AUTHENTICATED = "AUTHENTICATED";

    private Set<String> allowedMethods = Collections.singleton("GET");
    private Set<String> roles = Collections.singleton(AUTHENTICATED);
    private boolean audit;
    private boolean allowDuringPasswordChange;

    public void setAllowedMethods(String methods) { this.allowedMethods = csv(methods); }

    public void setRoles(String roles) { this.roles = csv(roles); }

    public void setAudit(String audit) { this.audit = Boolean.parseBoolean(audit.trim()); }

    public void setAllowDuringPasswordChange(String allow) { this.allowDuringPasswordChange = Boolean.parseBoolean(allow.trim()); }

    @Override
    public String intercept(ActionInvocation invocation) throws Exception {
        HttpServletRequest request = ServletActionContext.getRequest();
        HttpServletResponse response = ServletActionContext.getResponse();
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");

        Object action = invocation.getAction();
        String actionName = invocation.getProxy().getActionName();
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        if (!allowedMethods.contains(method)) {
            response.setHeader("Allow", String.join(", ", allowedMethods));
            return reject(action, ErrorCode.METHOD_NOT_ALLOWED);
        }
        if (!"GET".equals(method)) {
            String contentType = request.getContentType();
            boolean json = contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("application/json");
            boolean ajax = "XMLHttpRequest".equals(request.getHeader("X-Requested-With"));
            if (!json || !ajax) return reject(action, ErrorCode.FORBIDDEN);
        }

        LoginUser user = sessionUser(request);
        if (!roles.contains(PUBLIC)) {
            if (user == null) return reject(action, ErrorCode.UNAUTHENTICATED);
            if (user.isMustChangePassword() && !allowDuringPasswordChange) {
                return reject(action, ErrorCode.PASSWORD_CHANGE_REQUIRED);
            }
            if (!roles.contains(AUTHENTICATED) && !roles.contains(user.getRole())) {
                AuditService.getDefault().record(user, null, actionName, null, AuditService.DENIED,
                        ErrorCode.FORBIDDEN.name(), clientIp(request));
                return reject(action, ErrorCode.FORBIDDEN);
            }
        }
        if (action instanceof BaseApiAction) ((BaseApiAction) action).setLoginUser(user);

        BaseApiAction api = action instanceof BaseApiAction ? (BaseApiAction) action : null;
        final String[] replayed = {null};
        if (api != null && !"GET".equals(method)) {
            // Runs after the action (its transaction is committed) and before the JSON result is rendered
            invocation.addPreResultListener((inv, resultCode) -> {
                String key = api.getRequestKey();
                if (key == null) return;
                String fingerprint = actionName + "|" + api.getAuditTarget();
                if ("success".equals(resultCode)) {
                    IdempotencyService.getDefault().saveResult(key, fingerprint, toJson(api.getResponse().get("data")));
                } else if (ErrorCode.DUPLICATE_REQUEST.name().equals(api.getAuditDetail())) {
                    String[] stored = IdempotencyService.getDefault().storedResult(key);
                    if (stored == null) {
                        inv.setResultCode(api.fail(ErrorCode.REQUEST_IN_PROGRESS));
                    } else if (!fingerprint.equals(stored[0])) {
                        inv.setResultCode(api.fail(ErrorCode.IDEMPOTENCY_KEY_REUSED));
                    } else {
                        api.replay(fromJson(stored[1]));
                        inv.setResultCode("success");
                        replayed[0] = "REPLAYED";
                    }
                }
            });
        }

        String result = invocation.invoke();
        String finalCode = invocation.getResultCode() != null ? invocation.getResultCode() : result;

        if (audit || !"GET".equals(method)) {
            LoginUser after = sessionUser(request);   // login sets it, logout clears it
            LoginUser who = after != null ? after : user;
            AuditService.getDefault().record(who, api == null ? null : api.getAuditUserHint(), actionName,
                    api == null ? null : api.getAuditTarget(),
                    "success".equals(finalCode) ? AuditService.SUCCESS : AuditService.FAILURE,
                    replayed[0] != null ? replayed[0] : api == null ? null : api.getAuditDetail(), clientIp(request),
                    api == null ? null : api.getRequestKey());
        }
        return finalCode;
    }

    static LoginUser sessionUser(HttpServletRequest request) {
        try {
            HttpSession session = request.getSession(false);
            return session == null ? null : (LoginUser) session.getAttribute(SESSION_USER);
        } catch (IllegalStateException invalidated) {
            return null;
        }
    }

    /**
     * 利用者の IP。フロントエンド（Next.js）経由の場合は X-Forwarded-For の先頭。
     * 本番では信頼できるプロキシからの X-Forwarded-For だけを採用すること。
     */
    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.trim().isEmpty()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    private static String toJson(Object data) {
        try {
            return new org.apache.struts2.json.DefaultJSONWriter().write(data);
        } catch (org.apache.struts2.json.JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object fromJson(String json) {
        try {
            return json == null ? null : new org.apache.struts2.json.JSONReader().read(json);
        } catch (org.apache.struts2.json.JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String reject(Object action, ErrorCode code) {
        if (action instanceof BaseApiAction) return ((BaseApiAction) action).fail(code);
        return code.resultName();
    }

    private static Set<String> csv(String values) {
        return Arrays.stream(values.split(","))
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(HashSet::new));
    }
}
