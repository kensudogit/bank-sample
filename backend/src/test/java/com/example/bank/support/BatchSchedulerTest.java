package com.example.bank.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** 夜間バッチの起動：BANK_JOBS_ENABLED が true でなければ何も起動せず、停止も安全に呼べる。 */
class BatchSchedulerTest {
    @Test
    void disabledByDefaultAndStopIsSafe() {
        assumeFalse("true".equalsIgnoreCase(System.getenv("BANK_JOBS_ENABLED")), "jobs are enabled in this environment");
        BatchScheduler scheduler = new BatchScheduler();
        // 無効なときはスレッドも DB 接続も作らない（イベントの中身も参照しない）
        assertDoesNotThrow(() -> scheduler.contextInitialized(null));
        assertDoesNotThrow(() -> scheduler.contextDestroyed(null));
    }

    @Test
    void stopWithoutStartIsSafe() {
        assertDoesNotThrow(() -> new BatchScheduler().contextDestroyed(null));
    }
}
