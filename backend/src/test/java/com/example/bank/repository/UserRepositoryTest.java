package com.example.bank.repository;

import com.example.bank.domain.NewCustomer;
import com.example.bank.service.TestDatabase;
import com.example.bank.support.PasswordHasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** 利用者の DB アクセス：取得、ログイン成功・失敗の記録、パスワード変更、顧客ログインの発行。 */
class UserRepositoryTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 10, 0);
    private final UserRepository repo = new UserRepository();
    private Connection c;

    @BeforeEach
    void setUp() throws SQLException {
        c = TestDatabase.create().getConnection();
    }

    @AfterEach
    void tearDown() throws SQLException {
        c.close();
    }

    @Test
    void findSeededUsers() throws SQLException {
        UserRepository.UserRecord teller = repo.findById(c, "teller1").orElseThrow(AssertionError::new);
        assertEquals("TELLER", teller.role);
        assertNull(teller.customerId);
        assertTrue(PasswordHasher.verify("BankDemo#2026", teller.passwordHash));
        UserRepository.UserRecord yamada = repo.findById(c, "yamada").orElseThrow(AssertionError::new);
        assertEquals("CUSTOMER", yamada.role);
        assertEquals("CU0001", yamada.customerId);
        assertFalse(repo.findById(c, "nobody").isPresent());
        assertTrue(repo.lockById(c, "teller1").isPresent());
    }

    @Test
    void existence() throws SQLException {
        assertTrue(repo.exists(c, "teller1"));
        assertFalse(repo.exists(c, "nobody"));
        assertTrue(repo.existsForCustomer(c, "CU0001"));
        assertFalse(repo.existsForCustomer(c, "CU9999"));
    }

    @Test
    void failuresAndSuccess() throws SQLException {
        repo.recordFailure(c, "teller1", 3, NOW.plusMinutes(15));
        UserRepository.UserRecord locked = repo.findById(c, "teller1").get();
        assertEquals(3, locked.failedCount);
        assertEquals(NOW.plusMinutes(15), locked.lockedUntil);
        repo.recordSuccess(c, "teller1", NOW.plusMinutes(20));
        UserRepository.UserRecord ok = repo.findById(c, "teller1").get();
        assertEquals(0, ok.failedCount);
        assertNull(ok.lockedUntil);
    }

    @Test
    void updatePasswordAndMustChangeFlag() throws SQLException {
        String hash = PasswordHasher.hash("NewPassword1");
        repo.updatePassword(c, "yamada", hash, true, NOW);
        UserRepository.UserRecord u = repo.findById(c, "yamada").get();
        assertTrue(u.mustChangePassword);
        assertTrue(PasswordHasher.verify("NewPassword1", u.passwordHash));
        repo.updatePassword(c, "yamada", hash, false, NOW);
        assertFalse(repo.findById(c, "yamada").get().mustChangePassword);
    }

    @Test
    void insertCustomerUserMustChangePassword() throws SQLException {
        // 顧客 1 人にログインは 1 つだけなので、ログインのない顧客を作ってから発行する
        CustomerRepository customers = new CustomerRepository();
        String customerId = customers.nextCustomerId(c);
        customers.insert(c, customerId, new NewCustomer("新規 顧客", "シンキ コキャク", LocalDate.of(1990, 1, 1), null, null,
                null, "DRIVER_LICENSE", "123456789012"), "teller1", NOW);
        assertFalse(repo.existsForCustomer(c, customerId));
        repo.insertCustomerUser(c, "newcust", "新規 顧客", customerId, PasswordHasher.hash("Initial1234"), NOW);
        UserRepository.UserRecord u = repo.findById(c, "newcust").orElseThrow(AssertionError::new);
        assertEquals("CUSTOMER", u.role);
        assertEquals(customerId, u.customerId);
        assertTrue(repo.existsForCustomer(c, customerId));
        assertTrue(u.mustChangePassword);
        assertTrue(u.enabled);
    }
}
