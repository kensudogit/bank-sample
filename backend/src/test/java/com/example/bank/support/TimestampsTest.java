package com.example.bank.support;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** JSON 用の日時：秒まで・タイムゾーンなし、null はそのまま null。 */
class TimestampsTest {
    @Test
    void formatsToSecondsWithoutZone() {
        LocalDateTime t = LocalDateTime.of(2026, 11, 20, 9, 5, 7, 999_000_000);
        assertEquals("2026-11-20T09:05:07", Timestamps.format(t));
        assertEquals("2026-11-20T09:05:07", Timestamps.format(Timestamp.valueOf(t)));
    }

    @Test
    void nullStaysNull() {
        assertNull(Timestamps.format((Timestamp) null));
        assertNull(Timestamps.format((LocalDateTime) null));
    }
}
