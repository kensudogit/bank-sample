package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.domain.LoginUser;
import com.example.bank.interceptor.ApiGuardInterceptor;
import com.example.bank.service.AuthService;
import com.example.bank.service.BankService;
import org.apache.struts2.ServletActionContext;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.regex.Pattern;

/**
 * POST /api/login           {"userId","password"}               (method="login")
 * POST /api/logout          {}                                  (method="logout")
 * GET  /api/me                                                  (method="me")  ログイン中の利用者（未ログインは data=null）
 * POST /api/password-change {"currentPassword","newPassword"}   (method="changePassword")
 */
public class AuthAction extends BaseApiAction {
    // 利用者 ID の形式を先に確かめ、明らかに不正な入力では DB 照会やハッシュ計算をしない
    private static final Pattern USER_ID = Pattern.compile("^[a-z0-9._-]{3,30}$");

    private final AuthService auth;
    private String userId;
    private String password;
    private String currentPassword;
    private String newPassword;

    /** Struts が使う既定コンストラクタ。 */
    public AuthAction() { this(AuthService.getDefault()); }

    /** @param auth 認証サービス（テスト用） */
    public AuthAction(AuthService auth) {
        super(BankService.getDefault());
        this.auth = auth;
    }

    /**
     * ログインする。成功時はセッション ID を振り直してから利用者をセッションに保存する。
     *
     * @return success、入力不正なら input、認証失敗・ロック中なら unauthorized
     */
    public String login() {
        userId = userId == null ? null : userId.trim().toLowerCase();
        if (userId == null || !USER_ID.matcher(userId).matches() || password == null || password.isEmpty()
                // 極端に長いパスワードは PBKDF2 の計算負荷を上げる攻撃になるため、ハッシュ計算前に拒否する
                || password.length() > 128) {
            addFieldError("userId", getText("validation.login"));
            return INPUT;
        }
        try {
            LoginUser user = auth.login(userId, password);
            HttpServletRequest request = ServletActionContext.getRequest();
            // ログイン前のセッション ID を使い続けると、攻撃者が事前に仕込んだ ID でなりすませる（セッション固定攻撃）ため破棄する
            // New session id after login (session fixation)
            HttpSession old = request.getSession(false);
            if (old != null) old.invalidate();
            request.getSession(true).setAttribute(ApiGuardInterceptor.SESSION_USER, user);
            return ok(user);
        } catch (BankException e) {
            return fail(e);
        } finally {
            // 平文のパスワードを Action に残さない（ログやデバッグ出力に出ないように）
            password = null;
        }
    }

    /**
     * ログアウトする（セッションを破棄する）。
     *
     * @return success
     */
    public String logout() {
        HttpSession session = ServletActionContext.getRequest().getSession(false);
        if (session != null) session.invalidate();
        return ok(null);
    }

    /**
     * ログイン中の利用者を返す。
     *
     * @return success（未ログインなら data は null）
     */
    public String me() {
        return ok(currentUser());
    }

    /**
     * パスワードを変更し、セッション上の利用者を「変更済み」に差し替える。
     *
     * @return success、入力不正なら input、現在のパスワード誤り・ポリシー違反ならエラーの結果名
     */
    public String changePassword() {
        if (currentPassword == null || newPassword == null || newPassword.length() > 128) {
            addFieldError("newPassword", getText("validation.password"));
            return INPUT;
        }
        try {
            LoginUser updated = auth.changePassword(currentUser(), currentPassword, newPassword);
            ServletActionContext.getRequest().getSession(true).setAttribute(ApiGuardInterceptor.SESSION_USER, updated);
            return ok(updated);
        } catch (BankException e) {
            return fail(e);
        } finally {
            currentPassword = null;
            newPassword = null;
        }
    }

    /** @return ログイン試行時の利用者 ID（未ログインでも監査ログに誰の試行かを残すため） */
    @Override
    public String getAuditUserHint() { return userId; }

    /** @param userId 利用者 ID */
    @StrutsParameter public void setUserId(String userId) { this.userId = userId; }
    /** @param password パスワード */
    @StrutsParameter public void setPassword(String password) { this.password = password; }
    /** @param currentPassword 現在のパスワード */
    @StrutsParameter public void setCurrentPassword(String currentPassword) { this.currentPassword = currentPassword; }
    /** @param newPassword 新しいパスワード */
    @StrutsParameter public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
}
