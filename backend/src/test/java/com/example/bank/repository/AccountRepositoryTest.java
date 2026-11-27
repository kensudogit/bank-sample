package com.example.bank.repository;

import com.example.bank.domain.Account;
import com.example.bank.service.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 口座の DB アクセス：検索・集計・採番・残高更新・解約、休眠の条件付き更新。初期データ（db/init）を使う。 */
class AccountRepositoryTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 10, 0);
    private final AccountRepository repo = new AccountRepository();
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
    void findByIdAndOwner() throws SQLException {
        Account a = repo.findById(c, "1000001").orElseThrow(AssertionError::new);
        assertEquals("CU0001", a.getCustomerId());
        assertEquals("山田 太郎", a.getCustomerName());
        assertEquals("ORDINARY", a.getAccountType());
        assertEquals("JPY", a.getCurrency());
        assertEquals("CU0001", repo.findOwner(c, "1000001").orElse(null));
        assertFalse(repo.findById(c, "9999999").isPresent());
        assertFalse(repo.findOwner(c, "9999999").isPresent());
        assertTrue(repo.lockById(c, "1000001").isPresent());
    }

    @Test
    void findByCustomerAndStatus() throws SQLException {
        List<String> nos = repo.findByCustomer(c, "CU0001").stream().map(Account::getAccountNo).collect(Collectors.toList());
        assertTrue(nos.contains("1000001"));
        assertTrue(nos.contains("1000002"));
        assertTrue(repo.findByStatus(c, "FROZEN").stream().anyMatch(a -> "1000005".equals(a.getAccountNo())));
        assertTrue(repo.findByStatus(c, "FROZEN").stream().allMatch(a -> "FROZEN".equals(a.getStatus())));
    }

    @Test
    void searchAndSummaryRespectCustomerScope() throws SQLException {
        AccountRepository.Summary all = repo.summarize(c, null, null);
        AccountRepository.Summary own = repo.summarize(c, "CU0001", null);
        assertTrue(all.count > own.count);
        assertEquals(repo.findByCustomer(c, "CU0001").size(), own.count);
        BigDecimal ownTotal = repo.findByCustomer(c, "CU0001").stream().map(Account::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, ownTotal.compareTo(own.totalBalance));
        // 顧客の範囲では、他人の口座番号を検索しても出てこない
        assertTrue(repo.search(c, "CU0001", "1000003", 0, 50).isEmpty());
        assertEquals("1000003", repo.search(c, null, "1000003", 0, 50).get(0).getAccountNo());
    }

    @Test
    void searchPagesWithOffsetAndLimit() throws SQLException {
        List<Account> first = repo.search(c, null, null, 0, 2);
        List<Account> second = repo.search(c, null, null, 2, 2);
        assertEquals(2, first.size());
        assertFalse(second.isEmpty());
        assertNotEquals(first.get(0).getAccountNo(), second.get(0).getAccountNo());
    }

    @Test
    void insertWithNumberingAndCounts() throws SQLException {
        String no1 = repo.nextAccountNo(c);
        String no2 = repo.nextAccountNo(c);
        assertNotEquals(no1, no2);
        int before = repo.countOpenByCustomerAndType(c, "CU0001", "ORDINARY");
        repo.insert(c, no1, "CU0001", "ORDINARY", new BigDecimal("1000"), NOW);
        assertEquals(before + 1, repo.countOpenByCustomerAndType(c, "CU0001", "ORDINARY"));
        repo.insert(c, no2, "CU0001", "FOREIGN", "USD", new BigDecimal("100"), NOW);
        Account usd = repo.findById(c, no2).orElseThrow(AssertionError::new);
        assertEquals("USD", usd.getCurrency());
        assertEquals(1, repo.countOpenByCustomerTypeCurrency(c, "CU0001", "FOREIGN", "USD"));
        assertEquals(0, repo.countOpenByCustomerTypeCurrency(c, "CU0001", "FOREIGN", "EUR"));
        assertTrue(repo.findOpenAccountNos(c).contains(no1));
    }

    @Test
    void updateBalancesAndOverdraft() throws SQLException {
        repo.updateBalance(c, "1000001", new BigDecimal("1"), NOW, true);
        assertEquals(0, BigDecimal.ONE.compareTo(repo.findById(c, "1000001").get().getBalance()));
        repo.updateBalances(c, "1000001", new BigDecimal("2"), "1000003", new BigDecimal("3"), NOW);
        assertEquals(0, new BigDecimal("2").compareTo(repo.findById(c, "1000001").get().getBalance()));
        assertEquals(0, new BigDecimal("3").compareTo(repo.findById(c, "1000003").get().getBalance()));
        repo.setOverdraftLimit(c, "1000001", new BigDecimal("500"), NOW);
        assertEquals(0, new BigDecimal("502").compareTo(repo.findById(c, "1000001").get().getAvailableBalance()));
    }

    @Test
    void closeSetsZeroAndClosed() throws SQLException {
        repo.close(c, "1000004", NOW);
        Account a = repo.findById(c, "1000004").get();
        assertEquals("CLOSED", a.getStatus());
        assertEquals(0, a.getBalance().signum());
        assertNotNull(a.getClosedAt());
        assertFalse(repo.findOpenAccountNos(c).contains("1000004"));
    }

    @Test
    void dormancyUpdatesRecheckConditions() throws SQLException {
        // 1000007 は 2017 年から異動なし、1000001 は最近の取引がある
        LocalDateTime cutoff = NOW.minusYears(9);
        assertTrue(repo.findActiveInactiveSince(c, cutoff).stream().anyMatch(a -> "1000007".equals(a.getAccountNo())));
        assertEquals(0, repo.markNotified(c, "1000001", NOW, cutoff, NOW));
        assertEquals(1, repo.markNotified(c, "1000007", NOW, cutoff, NOW));
        // 通知済みには二度通知しない
        assertEquals(0, repo.markNotified(c, "1000007", NOW, cutoff, NOW));
        assertEquals(1, repo.markDormant(c, "1000007", NOW, cutoff, NOW));
        assertEquals("DORMANT", repo.findById(c, "1000007").get().getStatus());
        assertEquals(0, repo.markDormant(c, "1000007", NOW, cutoff, NOW));
        repo.reactivate(c, "1000007", NOW);
        Account a = repo.findById(c, "1000007").get();
        assertEquals("ACTIVE", a.getStatus());
        assertNull(a.getDormantAt());
        assertNull(a.getDormancyNotifiedAt());
    }

    @Test
    void customerActivityUpdatesLastActivityButBankProcessingDoesNot() throws SQLException {
        String before = repo.findById(c, "1000007").get().getLastActivityAt();
        repo.updateBalance(c, "1000007", new BigDecimal("250001"), NOW, false);
        assertEquals(before, repo.findById(c, "1000007").get().getLastActivityAt());
        repo.updateBalance(c, "1000007", new BigDecimal("250002"), NOW, true);
        assertNotEquals(before, repo.findById(c, "1000007").get().getLastActivityAt());
    }
}
