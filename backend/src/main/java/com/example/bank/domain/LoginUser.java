package com.example.bank.domain;

import java.io.Serializable;

/** ログイン中の利用者（HTTP セッションに保持する）。パスワード等の秘密情報は持たない。 */
public final class LoginUser implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String CUSTOMER = "CUSTOMER";
    public static final String TELLER = "TELLER";
    public static final String SUPERVISOR = "SUPERVISOR";
    /** 夜間バッチなどシステム処理の監査ログ上の利用者 */
    public static final LoginUser SYSTEM = new LoginUser("SYSTEM", "システム", "SYSTEM", null, false);

    private final String userId;
    private final String displayName;
    private final String role;
    private final String customerId;
    private final boolean mustChangePassword;

    public LoginUser(String userId, String displayName, String role, String customerId, boolean mustChangePassword) {
        this.userId = userId;
        this.displayName = displayName;
        this.role = role;
        this.customerId = customerId;
        this.mustChangePassword = mustChangePassword;
    }

    public LoginUser withPasswordChanged() {
        return new LoginUser(userId, displayName, role, customerId, false);
    }

    public String getUserId() { return userId; }
    public String getDisplayName() { return displayName; }
    public String getRole() { return role; }
    /** CUSTOMER のみ: 本人の顧客番号 */
    public String getCustomerId() { return customerId; }
    public boolean isMustChangePassword() { return mustChangePassword; }

    public boolean isCustomer() { return CUSTOMER.equals(role); }
    public boolean isStaff() { return TELLER.equals(role) || SUPERVISOR.equals(role); }
}
