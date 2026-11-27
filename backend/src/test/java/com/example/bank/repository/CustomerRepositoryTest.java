package com.example.bank.repository;

import com.example.bank.domain.Customer;
import com.example.bank.domain.NewCustomer;
import com.example.bank.service.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** 顧客の DB アクセス：採番・登録（書類番号は下 4 桁だけ）・名寄せ・本人確認の判定。 */
class CustomerRepositoryTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 10, 0);
    private final CustomerRepository repo = new CustomerRepository();
    private Connection c;

    @BeforeEach
    void setUp() throws SQLException {
        c = TestDatabase.create().getConnection();
    }

    @AfterEach
    void tearDown() throws SQLException {
        c.close();
    }

    private static NewCustomer newCustomer() {
        return new NewCustomer("試験 花子", "シケン ハナコ", LocalDate.of(1990, 5, 1), "100-0001", "東京都千代田区",
                "03-1234-5678", "DRIVER_LICENSE", "123456789012");
    }

    @Test
    void insertStoresOnlyTheLastFourDigitsOfTheIdDocument() throws SQLException {
        String id = repo.nextCustomerId(c);
        assertTrue(id.startsWith("CU"), id);
        repo.insert(c, id, newCustomer(), "teller1", NOW);
        Customer saved = repo.findById(c, id).orElseThrow(AssertionError::new);
        assertEquals("試験 花子", saved.getName());
        assertEquals("シケン ハナコ", saved.getNameKana());
        assertEquals("9012", saved.getIdDocLast4());
        assertEquals("teller1", saved.getRegisteredBy());
        assertFalse(saved.isVerified());
        assertEquals("INDIVIDUAL", saved.getCustomerType());
    }

    @Test
    void nextCustomerIdIsUnique() throws SQLException {
        assertNotEquals(repo.nextCustomerId(c), repo.nextCustomerId(c));
    }

    @Test
    void nameCheckByKanaAndBirthDate() throws SQLException {
        assertFalse(repo.existsByKanaAndBirthDate(c, "シケン ハナコ", LocalDate.of(1990, 5, 1)));
        repo.insert(c, repo.nextCustomerId(c), newCustomer(), "teller1", NOW);
        assertTrue(repo.existsByKanaAndBirthDate(c, "シケン ハナコ", LocalDate.of(1990, 5, 1)));
        assertFalse(repo.existsByKanaAndBirthDate(c, "シケン ハナコ", LocalDate.of(1990, 5, 2)));
    }

    @Test
    void findAllAndLock() throws SQLException {
        assertTrue(repo.findAll(c).stream().anyMatch(x -> "CU0001".equals(x.getCustomerId())));
        assertTrue(repo.lockById(c, "CU0001").isPresent());
        assertFalse(repo.findById(c, "CU9999").isPresent());
    }

    @Test
    void decideKycRecordsDecision() throws SQLException {
        String id = repo.nextCustomerId(c);
        repo.insert(c, id, newCustomer(), "teller1", NOW);
        repo.decideKyc(c, id, "VERIFIED", "免許証で確認", "supervisor1", NOW.plusHours(1));
        Customer decided = repo.findById(c, id).get();
        assertTrue(decided.isVerified());
        assertEquals("免許証で確認", decided.getKycNote());
        assertEquals("supervisor1", decided.getKycDecidedBy());
        assertNotNull(decided.getKycDecidedAt());
    }
}
