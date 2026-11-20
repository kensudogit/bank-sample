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
    public static final int MAX_FAILURES = 5;
    public static final int LOCK_MINUTES = 15;

    private static final AuthService DEFAULT = new AuthService(DataSources::bank, Clock.systemDefaultZone());

    private final Supplier<DataSource> dataSource;
    private final Clock clock;
    private final UserRepository users = new UserRepository();
    private final CustomerRepository customers = new CustomerRepository();

    public AuthService(Supplier<DataSource> dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    public static AuthService getDefault() { return DEFAULT; }

    /** ログイン。失敗時は AUTH_FAILED（ロック中は ACCOUNT_LOCKED）。 */
    public LoginUser login(String userId, String password) {
        // Verify the password outside the row lock: PBKDF2 is deliberately slow
        Optional<UserRecord> found = Transactions.readOnly(dataSource.get(), c -> users.findById(c, userId));
        UserRecord user = found.orElse(null);
        boolean passwordOk = PasswordHasher.verify(password, user == null ? null : user.passwordHash);
        if (user == null || !user.enabled) throw new BankException(ErrorCode.AUTH_FAILED);

        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            UserRecord current = users.lockById(c, userId).orElseThrow(() -> new BankException(ErrorCode.AUTH_FAILED));
            if (current.lockedUntil != null && current.lockedUntil.isAfter(now)) {
                throw new BankException(ErrorCode.ACCOUNT_LOCKED, String.valueOf(LOCK_MINUTES));
            }
            if (!passwordOk) {
                int failures = current.failedCount + 1;
                LocalDateTime lockUntil = failures >= MAX_FAILURES ? now.plusMinutes(LOCK_MINUTES) : null;
                users.recordFailure(c, userId, lockUntil == null ? failures : 0, lockUntil);
                // Commit the failure count before reporting the error
                c.commit();
                throw new BankException(lockUntil == null ? ErrorCode.AUTH_FAILED : ErrorCode.ACCOUNT_LOCKED,
                        String.valueOf(LOCK_MINUTES));
            }
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
        String initialPassword = PasswordHasher.randomPassword(12);
        String hash = PasswordHasher.hash(initialPassword);
        return Transactions.inTransaction(dataSource.get(), c -> {
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
