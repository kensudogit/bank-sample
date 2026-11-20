package com.example.bank.support;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;

/** 銀行営業日（土日と HOLIDAY 表の祝日・年末年始を除く）。 */
public final class BusinessCalendar {
    private BusinessCalendar() {}

    public static boolean isBusinessDay(Connection c, LocalDate date) throws SQLException {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM holiday WHERE holiday_date = ?")) {
            ps.setDate(1, Date.valueOf(date));
            try (ResultSet rs = ps.executeQuery()) {
                return !rs.next();
            }
        }
    }

    /** date が営業日ならそのまま、そうでなければ翌営業日。 */
    public static LocalDate onOrAfter(Connection c, LocalDate date) throws SQLException {
        LocalDate d = date;
        while (!isBusinessDay(c, d)) d = d.plusDays(1);
        return d;
    }

    /** date の翌営業日。 */
    public static LocalDate next(Connection c, LocalDate date) throws SQLException {
        return onOrAfter(c, date.plusDays(1));
    }
}
