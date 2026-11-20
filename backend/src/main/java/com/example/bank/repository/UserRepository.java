package com.example.bank.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

/** APP_USER（顧客・行員の利用者）への JDBC アクセス。 */
public class UserRepository {

    /** 認証用の利用者レコード（パスワードハッシュを含むためセッションや応答には載せない）。 */
    public static final class UserRecord {
        public final String userId;
        public final String displayName;
        public final String role;
        public final String customerId;
        public final String passwordHash;
        public final boolean mustChangePassword;
        public final int failedCount;
        public final LocalDateTime lockedUntil;
        public final boolean enabled;

        UserRecord(ResultSet rs) throws SQLException {
            userId = rs.getString("user_id");
            displayName = rs.getString("display_name");
            role = rs.getString("role");
            customerId = rs.getString("customer_id");
            passwordHash = rs.getString("password_hash");
            mustChangePassword = rs.getInt("must_change_password") == 1;
            failedCount = rs.getInt("failed_count");
            Timestamp locked = rs.getTimestamp("locked_until");
            lockedUntil = locked == null ? null : locked.toLocalDateTime();
            enabled = rs.getInt("enabled") == 1;
        }
    }

    private static final String SELECT = "SELECT user_id, display_name, role, customer_id, password_hash, "
            + "must_change_password, failed_count, locked_until, enabled FROM app_user ";

    /**
     * 利用者を取得する（ロックしない照会用）。
     *
     * @param c      DB 接続
     * @param userId 利用者 ID
     * @return 利用者（存在しなければ空）
     * @throws SQLException DB アクセスに失敗した場合
     */
    public Optional<UserRecord> findById(Connection c, String userId) throws SQLException {
        return one(c, SELECT + "WHERE user_id = ?", userId);
    }

    /** 行ロック付き（トランザクション内でのみ使用すること）。 */
    public Optional<UserRecord> lockById(Connection c, String userId) throws SQLException {
        // ログイン失敗回数の更新を同時ログインで取りこぼさないよう、認証時は利用者行をロックして読む
        return one(c, SELECT + "WHERE user_id = ? FOR UPDATE", userId);
    }

    // 1 行だけを取得する共通処理（照会用・ロック用の両方で使う）
    private static Optional<UserRecord> one(Connection c, String sql, String userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new UserRecord(rs)) : Optional.<UserRecord>empty();
            }
        }
    }

    /**
     * 顧客にログイン ID が発行済みかを返す（1 顧客 1 ログイン）。
     *
     * @param c          DB 接続
     * @param customerId 顧客番号
     * @return 発行済みなら true
     * @throws SQLException DB アクセスに失敗した場合
     */
    public boolean existsForCustomer(Connection c, String customerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM app_user WHERE customer_id = ?")) {
            ps.setString(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * 利用者 ID が使用済みかを返す。
     *
     * @param c      DB 接続
     * @param userId 利用者 ID
     * @return 使用済みなら true
     * @throws SQLException DB アクセスに失敗した場合
     */
    public boolean exists(Connection c, String userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM app_user WHERE user_id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * ログイン成功を記録する（失敗回数とロックを解除する）。
     *
     * @param c      DB 接続
     * @param userId 利用者 ID
     * @param now    ログイン日時
     * @throws SQLException DB アクセスに失敗した場合
     */
    public void recordSuccess(Connection c, String userId, LocalDateTime now) throws SQLException {
        execute(c, "UPDATE app_user SET failed_count = 0, locked_until = NULL, last_login_at = ? WHERE user_id = ?",
                Timestamp.valueOf(now), userId);
    }

    /**
     * ログイン失敗を記録する。
     *
     * @param c           DB 接続
     * @param userId      利用者 ID
     * @param failedCount 連続失敗回数
     * @param lockedUntil ロック期限（ロックしない場合は null）
     * @throws SQLException DB アクセスに失敗した場合
     */
    public void recordFailure(Connection c, String userId, int failedCount, LocalDateTime lockedUntil)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE app_user SET failed_count = ?, locked_until = ? WHERE user_id = ?")) {
            ps.setInt(1, failedCount);
            ps.setTimestamp(2, lockedUntil == null ? null : Timestamp.valueOf(lockedUntil));
            ps.setString(3, userId);
            ps.executeUpdate();
        }
    }

    /**
     * パスワードを更新する（失敗回数とロックも解除する）。
     *
     * @param c            DB 接続
     * @param userId       利用者 ID
     * @param passwordHash 新しいパスワードのハッシュ
     * @param mustChange   次回ログイン時に変更を求めるか（再発行時は true）
     * @param now          更新日時
     * @throws SQLException DB アクセスに失敗した場合
     */
    public void updatePassword(Connection c, String userId, String passwordHash, boolean mustChange, LocalDateTime now)
            throws SQLException {
        execute(c, "UPDATE app_user SET password_hash = ?, must_change_password = ?, password_changed_at = ?, "
                 + "failed_count = 0, locked_until = NULL WHERE user_id = ?",
                passwordHash, mustChange ? 1 : 0, Timestamp.valueOf(now), userId);
    }

    /**
     * 顧客のログイン利用者を登録する（初回ログイン時にパスワード変更を求める）。
     *
     * @param c            DB 接続
     * @param userId       利用者 ID
     * @param displayName  表示名
     * @param customerId   顧客番号
     * @param passwordHash 初期パスワードのハッシュ
     * @param now          登録日時
     * @throws SQLException DB アクセスに失敗した場合
     */
    public void insertCustomerUser(Connection c, String userId, String displayName, String customerId,
                                   String passwordHash, LocalDateTime now) throws SQLException {
        execute(c, "INSERT INTO app_user (user_id, display_name, role, customer_id, password_hash, must_change_password, "
                 + "password_changed_at, created_at) VALUES (?, ?, 'CUSTOMER', ?, ?, 1, ?, ?)",
                userId, displayName, customerId, passwordHash, Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    // 更新系 SQL を実行する共通処理
    private static void execute(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);   // all non-null here
            ps.executeUpdate();
        }
    }
}
