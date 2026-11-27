package com.example.bank.support;

import com.example.bank.service.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** 銀行営業日：土日と HOLIDAY 表（2026-11-23 勤労感謝の日、年末年始 12/31〜1/2）を除く。 */
class BusinessCalendarTest {
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
    void weekdaysAreBusinessDaysExceptHolidays() throws SQLException {
        assertTrue(BusinessCalendar.isBusinessDay(c, LocalDate.of(2026, 11, 20)));   // 金
        assertFalse(BusinessCalendar.isBusinessDay(c, LocalDate.of(2026, 11, 21)));  // 土
        assertFalse(BusinessCalendar.isBusinessDay(c, LocalDate.of(2026, 11, 22)));  // 日
        assertFalse(BusinessCalendar.isBusinessDay(c, LocalDate.of(2026, 11, 23)));  // 祝日（月）
        assertTrue(BusinessCalendar.isBusinessDay(c, LocalDate.of(2026, 11, 24)));
    }

    @Test
    void onOrAfterSkipsWeekendAndHoliday() throws SQLException {
        assertEquals(LocalDate.of(2026, 11, 20), BusinessCalendar.onOrAfter(c, LocalDate.of(2026, 11, 20)));
        assertEquals(LocalDate.of(2026, 11, 24), BusinessCalendar.onOrAfter(c, LocalDate.of(2026, 11, 21)));
    }

    @Test
    void nextIsTheFollowingBusinessDayAcrossYearEnd() throws SQLException {
        assertEquals(LocalDate.of(2026, 11, 24), BusinessCalendar.next(c, LocalDate.of(2026, 11, 20)));
        // 12/31（休業）・1/1（元日）・1/2（土・休業）・1/3（日）を越えて 1/4（月）
        assertEquals(LocalDate.of(2027, 1, 4), BusinessCalendar.next(c, LocalDate.of(2026, 12, 30)));
    }

    @Test
    void preloadedHolidaysGiveTheSameAnswers() throws SQLException {
        BusinessCalendar.Holidays h = BusinessCalendar.Holidays.load(c, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));
        for (LocalDate d = LocalDate.of(2026, 11, 1); d.isBefore(LocalDate.of(2026, 12, 15)); d = d.plusDays(1)) {
            assertEquals(BusinessCalendar.isBusinessDay(c, d), h.isBusinessDay(d), d.toString());
        }
        assertEquals(LocalDate.of(2026, 11, 24), h.onOrAfter(LocalDate.of(2026, 11, 21)));
    }

    @Test
    void preloadedHolidaysFetchBeyondTheLoadedRange() throws SQLException {
        // 11 月初めだけ読み込んでも、範囲外の年末年始は必要になった時点で読み込む
        BusinessCalendar.Holidays h = BusinessCalendar.Holidays.load(c, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2));
        assertFalse(h.isBusinessDay(LocalDate.of(2026, 12, 31)));
        assertFalse(h.isBusinessDay(LocalDate.of(2027, 1, 1)));
        assertEquals(LocalDate.of(2027, 1, 4), h.onOrAfter(LocalDate.of(2026, 12, 31)));
    }
}
