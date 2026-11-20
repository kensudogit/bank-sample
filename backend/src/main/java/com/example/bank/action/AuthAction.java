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
    private static final Pattern USER_ID = Pattern.compile("^[a-z0-9._-]{3,30}$");

    private final AuthService auth;
    private String userId;
    private String password;
    private String currentPassword;
    private String newPassword;

    public AuthAction() { this(AuthService.getDefault()); }

    public AuthAction(AuthService auth) {
        super(BankService.getDefault());
        this.auth = auth;
    }

    public String login() {
        userId = userId == null ? null : userId.trim().toLowerCase();
        if (userId == null || !USER_ID.matcher(userId).matches() || password == null || password.isEmpty()
                || password.length() > 128) {
            addFieldError("userId", getText("validation.login"));
            return INPUT;
        }
        try {
            LoginUser user = auth.login(userId, password);
            HttpServletRequest request = ServletActionContext.getRequest();
            // New session id after login (session fixation)
            HttpSession old = request.getSession(false);
            if (old != null) old.invalidate();
            request.getSession(true).setAttribute(ApiGuardInterceptor.SESSION_USER, user);
            return ok(user);
        } catch (BankException e) {
            return fail(e);
        } finally {
            password = null;
        }
    }

    public String logout() {
        HttpSession session = ServletActionContext.getRequest().getSession(false);
        if (session != null) session.invalidate();
        return ok(null);
    }

    public String me() {
        return ok(currentUser());
    }

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

    @Override
    public String getAuditUserHint() { return userId; }

    @StrutsParameter public void setUserId(String userId) { this.userId = userId; }
    @StrutsParameter public void setPassword(String password) { this.password = password; }
    @StrutsParameter public void setCurrentPassword(String currentPassword) { this.currentPassword = currentPassword; }
    @StrutsParameter public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
}
