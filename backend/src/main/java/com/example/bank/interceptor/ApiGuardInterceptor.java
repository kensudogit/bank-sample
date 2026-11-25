package com.example.bank.interceptor;

import com.example.bank.action.BaseApiAction;
import com.example.bank.domain.ErrorCode;
import com.example.bank.domain.LoginUser;
import com.example.bank.service.AuditService;
import com.example.bank.service.AuthService;
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
 *   <li>AI Agent からの要求（Kong が検証した On-Behalf-Of のトークン。X-Bank-Auth: delegated-token）は、
 *       行員の現在の権限（ロール）と Agent のスコープ（agentScope）の両方を満たすときだけ通す（参照のみ・既定は拒否）。
 *       Agent のメーター（アプリのみのトークン・ロール Agent.Meter）は AGENT ロールとして扱う。いずれも監査ログに Agent とトークン ID を残す</li>
 * </ul>
 * X-Bank-* の識別ヘッダーは、ゲートウェイ（GatewayGuardFilter が共有シークレットで確認）が送信元の値を消してから付けたものだけが届く。
 */
public class ApiGuardInterceptor extends AbstractInterceptor {
    /** ログイン中の利用者（{@link LoginUser}）を保存する HTTP セッション属性名 */
    public static final String SESSION_USER = "bank.loginUser";
    private static final String PUBLIC = "PUBLIC";
    private static final String AUTHENTICATED = "AUTHENTICATED";

    // 以下は struts.xml の interceptor-ref の param で Action ごとに設定される（既定は安全側: GET のみ・要ログイン）
    private Set<String> allowedMethods = Collections.singleton("GET");
    private Set<String> roles = Collections.singleton(AUTHENTICATED);
    private boolean audit;
    private boolean allowDuringPasswordChange;
    private String agentScope;

    /** Agent（委任トークン）が使える API とするときの必要スコープ（例 Accounts.Read）。未設定なら Agent からは使えない */
    public void setAgentScope(String scope) { this.agentScope = scope == null || scope.trim().isEmpty() ? null : scope.trim(); }

    /** @param methods 許可する HTTP メソッド（カンマ区切り） */
    public void setAllowedMethods(String methods) { this.allowedMethods = csv(methods); }

    /** @param roles 許可するロール（カンマ区切り。PUBLIC / AUTHENTICATED / ロール名） */
    public void setRoles(String roles) { this.roles = csv(roles); }

    /** @param audit true なら GET でも監査ログに記録する（個人情報の閲覧など） */
    public void setAudit(String audit) { this.audit = Boolean.parseBoolean(audit.trim()); }

    /** @param allow true ならパスワード変更前でも呼べる API とする（パスワード変更・ログアウトなど） */
    public void setAllowDuringPasswordChange(String allow) { this.allowDuringPasswordChange = Boolean.parseBoolean(allow.trim()); }

    /**
     * Action の実行前にメソッド・CSRF・認証・認可を確認し、実行後に冪等キーの結果保存と監査ログ記録を行う。
     *
     * @param invocation Action の呼び出し
     * @return 結果名（拒否した場合はエラーに対応する結果名）
     * @throws Exception Action 側の想定外例外（global-exception-mappings で 500 にする）
     */
    @Override
    public String intercept(ActionInvocation invocation) throws Exception {
        HttpServletRequest request = ServletActionContext.getRequest();
        HttpServletResponse response = ServletActionContext.getResponse();
        // JSON をブラウザが別の形式として解釈しないようにし、個人情報を含む応答をキャッシュさせない
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");

        Object action = invocation.getAction();
        String actionName = invocation.getProxy().getActionName();
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        if (!allowedMethods.contains(method)) {
            response.setHeader("Allow", String.join(", ", allowedMethods));
            return reject(action, ErrorCode.METHOD_NOT_ALLOWED);
        }
        // 他サイトのフォームからはこの 2 つのヘッダーを付けて送れないため、付いていない更新要求は CSRF とみなして拒否する
        if (!"GET".equals(method)) {
            String contentType = request.getContentType();
            boolean json = contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("application/json");
            boolean ajax = "XMLHttpRequest".equals(request.getHeader("X-Requested-With"));
            if (!json || !ajax) return reject(action, ErrorCode.FORBIDDEN);
        }

        // AI Agent からの要求：セッションではなく、Kong が検証したトークンのクレームで利用者を決める
        String auth = request.getHeader("X-Bank-Auth");
        String agentContext = null;
        LoginUser user;
        if ("delegated-token".equals(auth)) {
            String agentId = request.getHeader("X-Bank-Agent-Id");
            agentContext = "agent=" + agentId + " token=" + request.getHeader("X-Bank-Token-Id");
            Set<String> scopes = new HashSet<>(Arrays.asList(String.valueOf(request.getHeader("X-Bank-Scopes")).split(" ")));
            user = AuthService.getDefault().delegatedStaff(request.getHeader("X-Bank-Delegated-User"));
            // 参照の API で、その API のスコープを Agent が持っているときだけ（設定のない API は Agent から使えない＝既定は拒否）
            if (user == null || agentScope == null || !"GET".equals(method) || !scopes.contains(agentScope)) {
                AuditService.getDefault().record(user, request.getHeader("X-Bank-Delegated-User"), actionName, null,
                        AuditService.DENIED, "AGENT_SCOPE " + agentContext + " scope=" + agentScope, clientIp(request));
                return reject(action, user == null ? ErrorCode.UNAUTHENTICATED : ErrorCode.FORBIDDEN);
            }
        } else if ("app-token".equals(auth)) {
            String agentId = request.getHeader("X-Bank-Agent-Id");
            agentContext = "agent=" + agentId + " token=" + request.getHeader("X-Bank-Token-Id");
            Set<String> appRoles = new HashSet<>(Arrays.asList(String.valueOf(request.getHeader("X-Bank-Roles")).split(" ")));
            user = agentId != null && agentId.matches("^[A-Za-z0-9_.-]{1,40}$") && appRoles.contains("Agent.Meter")
                    ? new LoginUser("agent:" + agentId, "AI Agent " + agentId, LoginUser.AGENT, null, false) : null;
        } else {
            user = sessionUser(request);
        }
        if (!roles.contains(PUBLIC)) {
            if (user == null) return reject(action, ErrorCode.UNAUTHENTICATED);
            // 初期パスワードのまま業務 API を使わせない（発行時のパスワードを知る人が本人になりすますのを防ぐ）
            if (user.isMustChangePassword() && !allowDuringPasswordChange) {
                return reject(action, ErrorCode.PASSWORD_CHANGE_REQUIRED);
            }
            // 外部連携のサービスアカウントは、ロールを明示的に列挙した API だけ（AUTHENTICATED の API も不可）
            boolean listed = roles.contains(user.getRole());
            boolean service = LoginUser.INTEGRATION.equals(user.getRole()) || LoginUser.AGENT.equals(user.getRole());
            boolean allowed = service ? listed : (roles.contains(AUTHENTICATED) || listed);
            if (!allowed) {
                // 権限外の操作の試みは不正の兆候になり得るため、GET でも DENIED として記録する
                AuditService.getDefault().record(user, null, actionName, null, AuditService.DENIED,
                        ErrorCode.FORBIDDEN.name(), clientIp(request));
                return reject(action, ErrorCode.FORBIDDEN);
            }
        }
        if (action instanceof BaseApiAction) ((BaseApiAction) action).setLoginUser(user);

        BaseApiAction api = action instanceof BaseApiAction ? (BaseApiAction) action : null;
        // ラムダ式の中から値を書き戻すため、要素 1 つの配列で受け渡す（ローカル変数は実質 final でなければならない）
        final String[] replayed = {null};
        if (api != null && !"GET".equals(method)) {
            // Runs after the action (its transaction is committed) and before the JSON result is rendered
            invocation.addPreResultListener((inv, resultCode) -> {
                String key = api.getRequestKey();
                if (key == null) return;
                // 指紋 = 操作名 + 対象（口座・金額など）。同じキーで内容の違う要求が来たことを見分けるのに使う
                String fingerprint = actionName + "|" + api.getAuditTarget();
                // 成功した応答だけを保存する。業務エラーは保存しないので、同じキーで再送すると業務処理がもう一度判定される
                if ("success".equals(resultCode)) {
                    IdempotencyService.getDefault().saveResult(key, fingerprint, toJson(api.getResponse().get("data")));
                // サービスが「キー登録済み」で DUPLICATE_REQUEST を返した場合、保存済みの結果を見て応答を決め直す
                } else if (ErrorCode.DUPLICATE_REQUEST.name().equals(api.getAuditDetail())) {
                    String[] stored = IdempotencyService.getDefault().storedResult(key);
                    // 先の要求がまだコミット前（結果未保存）: 二重実行はしていないので「処理中」と返し、後で再送してもらう
                    if (stored == null) {
                        inv.setResultCode(api.fail(ErrorCode.REQUEST_IN_PROGRESS));
                    // 同じキーで別の内容: クライアントのキー再利用の誤り。前回の結果を返すと誤解を招くので拒否する
                    } else if (!fingerprint.equals(stored[0])) {
                        inv.setResultCode(api.fail(ErrorCode.IDEMPOTENCY_KEY_REUSED));
                    } else {
                        // 同じキー・同じ内容の再送: 業務処理は再実行せず、前回の成功結果をそのまま返す
                        api.replay(fromJson(stored[1]));
                        inv.setResultCode("success");
                        replayed[0] = "REPLAYED";
                    }
                }
            });
        }

        String result = invocation.invoke();
        // PreResultListener が結果名を差し替えた場合（再送の再現など）はそちらを採用する
        String finalCode = invocation.getResultCode() != null ? invocation.getResultCode() : result;

        // 更新系はすべて記録する。結果（成功・失敗）にかかわらず記録し、誰が何をしようとしたかを残す
        if (audit || !"GET".equals(method) || agentContext != null) {
            LoginUser after = agentContext != null ? null : sessionUser(request);   // login sets it, logout clears it
            LoginUser who = after != null ? after : user;
            final String via = agentContext;
            AuditService.getDefault().record(who, api == null ? null : api.getAuditUserHint(), actionName,
                    api == null ? null : api.getAuditTarget(),
                    "success".equals(finalCode) ? AuditService.SUCCESS : AuditService.FAILURE,
                    withAgent(replayed[0] != null ? replayed[0] : api == null ? null : api.getAuditDetail(), via), clientIp(request),
                    api == null ? null : api.getRequestKey());
        }
        return finalCode;
    }

    /**
     * セッション上のログイン利用者を返す（セッションを新規作成しない）。
     *
     * @param request HTTP リクエスト
     * @return ログイン利用者。未ログイン・セッション無効化済みなら null
     */
    static LoginUser sessionUser(HttpServletRequest request) {
        try {
            HttpSession session = request.getSession(false);
            return session == null ? null : (LoginUser) session.getAttribute(SESSION_USER);
        // ログアウトなどで同じリクエスト内でセッションが無効化された後は getAttribute が例外になる
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

    // 再送時に同じ応答を返せるよう、成功時の data を JSON 文字列にして保存する
    private static String toJson(Object data) {
        try {
            return new org.apache.struts2.json.DefaultJSONWriter().write(data);
        } catch (org.apache.struts2.json.JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // 保存した JSON を Map / List に戻す（JSON result で再びシリアライズされる）
    private static Object fromJson(String json) {
        try {
            return json == null ? null : new org.apache.struts2.json.JSONReader().read(json);
        } catch (org.apache.struts2.json.JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // 監査ログの詳細に、Agent 経由であること（Agent のクライアント ID とトークン ID）を加える
    private static String withAgent(String detail, String agentContext) {
        if (agentContext == null) return detail;
        return detail == null ? agentContext : detail + " " + agentContext;
    }

    // Action を実行せずにエラー応答を返す（BaseApiAction 以外では結果名だけを返す）
    private static String reject(Object action, ErrorCode code) {
        if (action instanceof BaseApiAction) return ((BaseApiAction) action).fail(code);
        return code.resultName();
    }

    // ロール名・メソッド名は大文字にそろえて比較する（設定の大文字小文字の違いで判定がずれないように）
    private static Set<String> csv(String values) {
        return Arrays.stream(values.split(","))
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(HashSet::new));
    }
}
