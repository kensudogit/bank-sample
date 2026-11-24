package com.example.bank.domain;

import java.io.Serializable;

/** ログイン中の利用者（HTTP セッションに保持する）。パスワード等の秘密情報は持たない。 */
public final class LoginUser implements Serializable {
    // HTTP セッションに格納され、Liberty のセッション永続化・複製でシリアライズされるため固定する
    private static final long serialVersionUID = 1L;

    /** ロール: 顧客（本人の口座のみ操作できる） */
    public static final String CUSTOMER = "CUSTOMER";
    /** ロール: 窓口担当の行員 */
    public static final String TELLER = "TELLER";
    /** ロール: 承認権限を持つ役席者 */
    public static final String SUPERVISOR = "SUPERVISOR";
    /**
     * ロール: 外部連携のサービスアカウント（MuleSoft）。struts.xml で INTEGRATION を明示した API だけを呼べ、
     * AUTHENTICATED（ログイン済みなら誰でも）の API も使えない（口座一覧などを読めないようにする最小権限）。
     */
    public static final String INTEGRATION = "INTEGRATION";
    /** 夜間バッチなどシステム処理の監査ログ上の利用者 */
    public static final LoginUser SYSTEM = new LoginUser("SYSTEM", "システム", "SYSTEM", null, false);

    private final String userId;
    private final String displayName;
    private final String role;
    private final String customerId;
    private final boolean mustChangePassword;

    /**
     * @param userId             利用者 ID
     * @param displayName        表示名
     * @param role               ロール（CUSTOMER / TELLER / SUPERVISOR / SYSTEM）
     * @param customerId         顧客の場合の顧客番号（行員は null）
     * @param mustChangePassword 初回ログインなどでパスワード変更が必要か
     */
    public LoginUser(String userId, String displayName, String role, String customerId, boolean mustChangePassword) {
        this.userId = userId;
        this.displayName = displayName;
        this.role = role;
        this.customerId = customerId;
        this.mustChangePassword = mustChangePassword;
    }

    /**
     * パスワード変更後の利用者を返す（セッション上の値を差し替えるため新しいインスタンスにする）。
     *
     * @return パスワード変更要求を解除した利用者
     */
    public LoginUser withPasswordChanged() {
        return new LoginUser(userId, displayName, role, customerId, false);
    }

    /** @return 利用者 ID */
    public String getUserId() { return userId; }
    /** @return 表示名 */
    public String getDisplayName() { return displayName; }
    /** @return ロール */
    public String getRole() { return role; }
    /** CUSTOMER のみ: 本人の顧客番号 */
    public String getCustomerId() { return customerId; }
    /** @return パスワード変更が必要なら true（変更するまで他の API を使わせない） */
    public boolean isMustChangePassword() { return mustChangePassword; }

    /** @return 顧客なら true */
    public boolean isCustomer() { return CUSTOMER.equals(role); }
    /** @return 行員（窓口・役席）なら true。SYSTEM は含まない */
    public boolean isStaff() { return TELLER.equals(role) || SUPERVISOR.equals(role); }
}
