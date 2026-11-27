package com.example.bank.repository;

import com.example.bank.domain.AccountTransaction;
import com.example.bank.domain.TxType;
import com.example.bank.service.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 取引明細の DB アクセス：登録・振込の対の明細、検索と集計、日次上限用の出金合計、期首残高。 */
class TransactionRepositoryTest {
    private static final LocalDateTime T = LocalDateTime.of(2030, 1, 10, 10, 0);
    private static final String NO = "1000004";
    private final TransactionRepository repo = new TransactionRepository();
    private Connection c;

    @BeforeEach
    void setUp() throws SQLException {
        c = TestDatabase.create().getConnection();
        // 初期データより後の日時で、入金・出金・振込を記帳する
        repo.insert(c, NO, TxType.DEPOSIT, new BigDecimal("10000"), new BigDecimal("130000"), null, "入金", null, T);
        repo.insert(c, NO, TxType.WITHDRAWAL, new BigDecimal("3000"), new BigDecimal("127000"), null, "出金", null, T.plusHours(1));
        repo.insertTransfer(c, NO, new BigDecimal("125000"), "1000003", new BigDecimal("482000"), new BigDecimal("2000"),
                "振替", "key-transfer-000000001", T.plusHours(2));
    }

    @AfterEach
    void tearDown() throws SQLException {
        c.close();
    }

    @Test
    void findRecentIsNewestFirst() throws SQLException {
        List<AccountTransaction> recent = repo.findRecent(c, NO, 3);
        assertEquals(3, recent.size());
        assertEquals("TRANSFER_OUT", recent.get(0).getTxType());
        assertEquals("1000003", recent.get(0).getCounterpartyAccountNo());
        assertEquals("WITHDRAWAL", recent.get(1).getTxType());
        assertEquals("DEPOSIT", recent.get(2).getTxType());
    }

    @Test
    void transferWritesBothSides() throws SQLException {
        AccountTransaction in = repo.findRecent(c, "1000003", 1).get(0);
        assertEquals("TRANSFER_IN", in.getTxType());
        assertEquals(NO, in.getCounterpartyAccountNo());
        assertEquals(0, new BigDecimal("2000").compareTo(in.getAmount()));
        assertEquals(0, new BigDecimal("482000").compareTo(in.getBalanceAfter()));
    }

    @Test
    void searchAndTotalsWithPeriodAndTypes() throws SQLException {
        TransactionRepository.Filter period = new TransactionRepository.Filter(NO, T, T.plusDays(1), null);
        TransactionRepository.Totals totals = repo.totals(c, period);
        assertEquals(3, totals.count);
        assertEquals(0, new BigDecimal("10000").compareTo(totals.totalIn));
        assertEquals(0, new BigDecimal("5000").compareTo(totals.totalOut));
        assertEquals(2, repo.search(c, period, 1, 10).size());

        TransactionRepository.Filter onlyDeposits = new TransactionRepository.Filter(NO, T, T.plusDays(1),
                Collections.singletonList(TxType.DEPOSIT));
        assertEquals(1, repo.totals(c, onlyDeposits).count);
        TransactionRepository.Filter outs = new TransactionRepository.Filter(NO, T, T.plusDays(1),
                Arrays.asList(TxType.WITHDRAWAL, TxType.TRANSFER_OUT));
        assertEquals(2, repo.search(c, outs, 0, 10).size());
    }

    @Test
    void outflowCountsOnlyWithdrawalsAndTransfersOut() throws SQLException {
        assertEquals(0, new BigDecimal("5000").compareTo(repo.outflowSince(c, NO, T)));
        assertEquals(0, new BigDecimal("2000").compareTo(repo.outflowSince(c, NO, T.plusMinutes(90))));
        assertEquals(0, BigDecimal.ZERO.compareTo(repo.outflowSince(c, NO, T.plusDays(1))));
    }

    @Test
    void balanceBeforeAndChanges() throws SQLException {
        assertEquals(0, new BigDecimal("130000").compareTo(repo.balanceBefore(c, NO, T.plusMinutes(30))));
        assertEquals(0, new BigDecimal("125000").compareTo(repo.balanceBefore(c, NO, T.plusDays(1))));
        assertEquals(0, BigDecimal.ZERO.compareTo(repo.balanceBefore(c, NO, LocalDateTime.of(2000, 1, 1, 0, 0))));
        assertEquals(3, repo.balanceChanges(c, NO, T, T.plusDays(1)).size());
        assertEquals(1, repo.balanceChanges(c, NO, T.plusMinutes(30), T.plusMinutes(90)).size());
    }
}
