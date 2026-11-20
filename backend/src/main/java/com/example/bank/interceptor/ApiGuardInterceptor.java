package com.example.bank.interceptor;

import com.example.bank.action.BaseApiAction;
import com.example.bank.domain.ErrorCode;
import com.opensymphony.xwork2.ActionInvocation;
import com.opensymphony.xwork2.interceptor.AbstractInterceptor;
import org.apache.struts2.ServletActionContext;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * API 共通ガード。
 * <ul>
 *   <li>許可 HTTP メソッド以外は 405</li>
 *   <li>更新系（GET 以外）は Content-Type: application/json と X-Requested-With: XMLHttpRequest を必須とする
 *       （クロスサイトの単純フォーム送信では付与できないため CSRF 対策になる）</li>
 *   <li>セキュリティ系レスポンスヘッダーを付与</li>
 * </ul>
 */
public class ApiGuardInterceptor extends AbstractInterceptor {
    private Set<String> allowedMethods = Collections.singleton("GET");

    public void setAllowedMethods(String methods) {
        this.allowedMethods = Arrays.stream(methods.split(","))
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    @Override
    public String intercept(ActionInvocation invocation) throws Exception {
        HttpServletRequest request = ServletActionContext.getRequest();
        HttpServletResponse response = ServletActionContext.getResponse();
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");

        Object action = invocation.getAction();
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
        return invocation.invoke();
    }

    private static String reject(Object action, ErrorCode code) {
        if (action instanceof BaseApiAction) return ((BaseApiAction) action).fail(code);
        return code.resultName();
    }
}
