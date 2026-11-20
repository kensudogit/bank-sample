package com.example.bank.service;

import com.example.bank.domain.BankException;
import com.example.bank.domain.Customer;
import com.example.bank.domain.ErrorCode;
import com.example.bank.domain.LoginUser;
import com.example.bank.repository.CustomerRepository;
import com.example.bank.repository.UserRepository;
import com.example.bank.repository.UserRepository.UserRecord;
import com.example.bank.support.DataSources;
import com.example.bank.support.PasswordHasher;
import com.example.bank.support.Transactions;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 認証（ログイン・パスワード変更・顧客ログインの発行）。
 * <ul>
 *   <li>パスワードは PBKDF2 ハッシュで保存（{@link PasswordHasher}）</li>
 *   <li>連続 {@value #MAX_FAILURES} 回失敗で {@value #LOCK_MINUTES} 分ロック。失敗理由（ID 不存在／パスワード誤り）は区別して返さない</li>
 *   <li>発行した初期パスワードは初回ログイン時に変更必須</li>
 * </ul>
 */
public class AuthService {
    /** この回数連続で失敗するとロックする */
    public static final int MAX_FAILURES = 5;
    /** ロックする時間（分） */
    public static final int LOCK_MINUTES = 15;

    private static final AuthService DEFAULT = new AuthService(DataSources::bank, Clock.systemDefaultZone());

    private final Supplier<DataSource> dataSource;
    private final Clock clock;
    private final UserRepository users = new UserRepository();
    private final CustomerRepository customers = new CustomerRepository();

    /**
     * @param dataSource 接続の取得元
     * @param clock      ロック期限の判定に使う時計
     */
    public AuthService(Supplier<DataSource> dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    /** @return アプリケーション既定のインスタンス */
    public static AuthService getDefault() { return DEFAULT; }

    /** ログイン。失敗時は AUTH_FAILED（ロック中は ACCOUNT_LOCKED）。 */
    public LoginUser login(String userId, String password) {
        // PBKDF2 は意図的に遅い（約 0.1 秒以上）。行ロック中に計算すると同じ利用者へのログインが詰まるため、ロックの外で先に照合する
        // Verify the password outside the row lock: PBKDF2 is deliberately slow
        Optional<UserRecord> found = Transactions.readOnly(dataSource.get(), c -> users.findById(c, userId));
        UserRecord user = found.orElse(null);
        boolean passwordOk = PasswordHasher.verify(password, user == null ? null : user.passwordHash);
        // 利用者が存在しなくても verify でダミー計算をした後に失敗させ、応答時間から ID の有無を推測させない
        if (user == null || !user.enabled) throw new BankException(ErrorCode.AUTH_FAILED);

        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            // 失敗回数の加算は「読む → +1 → 書く」なので、同時ログインで回数を取りこぼさないよう行ロックしてから読む
            UserRecord current = users.lockById(c, userId).orElseThrow(() -> new BankException(ErrorCode.AUTH_FAILED));
            if (current.lockedUntil != null && current.lockedUntil.isAfter(now)) {
                throw new BankException(ErrorCode.ACCOUNT_LOCKED, String.valueOf(LOCK_MINUTES));
            }
            if (!passwordOk) {
                int failures = current.failedCount + 1;
                LocalDateTime lockUntil = failures >= MAX_FAILURES ? now.plusMinutes(LOCK_MINUTES) : null;
                users.recordFailure(c, userId, lockUntil == null ? failures : 0, lockUntil);
                // 例外を投げると Transactions がロールバックするため、失敗回数だけは先にコミットして確実に残す（総当たり攻撃の対策）
                // Commit the failure count before reporting the error
                c.commit();
                throw new BankException(lockUntil == null ? ErrorCode.AUTH_FAILED : ErrorCode.ACCOUNT_LOCKED,
                        String.valueOf(LOCK_MINUTES));
            }
            // 成功したら失敗回数とロックをリセットする
            users.recordSuccess(c, userId, now);
            return new LoginUser(current.userId, current.displayName, current.role, current.customerId,
                    current.mustChangePassword);
        });
    }

    /** パスワード変更（現在のパスワードの確認が必要）。 */
    public LoginUser changePassword(LoginUser user, String currentPassword, String newPassword) {
        if (!PasswordHasher.meetsPolicy(newPassword) || newPassword.equalsIgnoreCase(user.getUserId())
                || newPassword.equals(currentPassword)) {
            throw new BankException(ErrorCode.PASSWORD_POLICY);
        }
        // ハッシュ計算（遅い処理）はトランザクションの外で先に済ませ、行ロックの時間を短くする
        String newHash = PasswordHasher.hash(newPassword);
        return Transactions.inTransaction(dataSource.get(), c -> {
            UserRecord current = users.lockById(c, user.getUserId())
                    .orElseThrow(() -> new BankException(ErrorCode.UNAUTHENTICATED));
            if (!PasswordHasher.verify(currentPassword, current.passwordHash)) {
                throw new BankException(ErrorCode.AUTH_FAILED);
            }
            users.updatePassword(c, user.getUserId(), newHash, false, LocalDateTime.now(clock));
            return user.withPasswordChanged();
        });
    }

    /**
     * 本人確認済みの顧客にオンラインバンキングのログインを発行する（窓口担当）。
     * 初期パスワードはこの応答でのみ返し（保存はハッシュのみ）、初回ログイン時に変更を求める。
     */
    public Map<String, String> issueCustomerLogin(String customerId) {
        // 初期パスワードの生成とハッシュ化はトランザクションの外で行う（ロック時間を短くする）
        String initialPassword = PasswordHasher.randomPassword(12);
        String hash = PasswordHasher.hash(initialPassword);
        return Transactions.inTransaction(dataSource.get(), c -> {
            // 顧客行をロックし、同じ顧客へのログイン発行が同時に 2 回走っても 1 件しか作られないようにする
            Customer customer = customers.lockById(c, customerId)
                    .orElseThrow(() -> new BankException(ErrorCode.CUSTOMER_NOT_FOUND, customerId));
            if (!customer.isVerified()) throw new BankException(ErrorCode.KYC_NOT_VERIFIED, customerId);
            if (users.existsForCustomer(c, customerId)) throw new BankException(ErrorCode.LOGIN_ALREADY_ISSUED);
            String userId = customerId.toLowerCase();
            users.insertCustomerUser(c, userId, customer.getName(), customerId, hash, LocalDateTime.now(clock));
            Map<String, String> result = new LinkedHashMap<>();
            result.put("userId", userId);
            result.put("initialPassword", initialPassword);
            return result;
        });
    }
}
