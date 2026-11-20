package com.example.bank.support;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class Timestamps {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private Timestamps() {}

    public static String format(Timestamp ts) { return ts == null ? null : ISO.format(ts.toLocalDateTime()); }

    public static String format(LocalDateTime t) { return t == null ? null : ISO.format(t); }
}
