package com.example.bank.support;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * ゲートウェイ迂回の防止：勘定系 API は Kong を経由した要求だけを受け付ける。
 * Kong は転送時に X-Bank-Gateway-Secret（環境変数 BANK_GATEWAY_SECRET と同じ値）を付け直す
 * （送信元が付けた同名ヘッダーは Kong が取り除く）。この値を知らない要求（社内ネットワークから Liberty を
 * 直接呼んだ場合など）は 403 にする。
 *
 * <ul>
 *   <li>死活確認（/api/live・/api/health）は対象外（Kong・コンテナのヘルスチェックが直接呼ぶ）</li>
 *   <li>BANK_GATEWAY_SECRET が未設定なら、すべての業務 API を 503 にする（設定漏れで素通しにしない）。
 *       ゲートウェイなしで動かす開発時だけ BANK_GATEWAY_SECRET=disabled-for-local-dev を明示する</li>
 * </ul>
 */
public class GatewayGuardFilter implements Filter {
    /** Kong が付けるヘッダー名 */
    public static final String HEADER = "X-Bank-Gateway-Secret";
    /** ゲートウェイの確認を明示的に外す値（開発専用） */
    public static final String DISABLED = "disabled-for-local-dev";
    private static final Logger LOG = LogManager.getLogger(GatewayGuardFilter.class);

    private byte[] secret;
    private boolean disabled;

    @Override
    public void init(FilterConfig config) {
        configure(System.getenv("BANK_GATEWAY_SECRET"));
    }

    /** @param value 共有シークレット（null・空なら業務 API を止める） */
    void configure(String value) {
        disabled = DISABLED.equals(value);
        if (disabled) {
            LOG.warn("BANK_GATEWAY_SECRET={}: requests that bypass the API gateway are accepted (development only)", DISABLED);
            secret = null;
        } else if (value == null || value.length() < 32) {
            LOG.error("BANK_GATEWAY_SECRET is not set (or shorter than 32 characters): business APIs answer 503");
            secret = null;
        } else {
            secret = value.getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (disabled || "/api/live".equals(path) || "/api/health".equals(path)) {
            chain.doFilter(req, res);
            return;
        }
        if (secret == null) {
            deny(response, 503, "SERVICE_UNAVAILABLE");
            return;
        }
        String presented = request.getHeader(HEADER);
        // 長さに関係なく一定時間で比べる（値を 1 文字ずつ推測されないように）
        if (presented == null || !MessageDigest.isEqual(secret, presented.getBytes(StandardCharsets.UTF_8))) {
            LOG.warn("request without the gateway secret rejected: {} {} from {}", request.getMethod(), path, request.getRemoteAddr());
            deny(response, 403, "FORBIDDEN");
            return;
        }
        chain.doFilter(req, res);
    }

    private static void deny(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"error\":{\"code\":\"" + code + "\",\"message\":\"API ゲートウェイ経由で呼び出してください。\"}}");
    }

    @Override
    public void destroy() { }
}
