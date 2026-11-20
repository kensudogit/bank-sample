package com.example.bank.support;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 日時を JSON 出力用の ISO-8601 文字列（秒まで、タイムゾーンなし）に整形する。 */
public final class Timestamps {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private Timestamps() {}

    /**
     * @param ts JDBC の日時（null 可）
     * @return yyyy-MM-ddTHH:mm:ss 形式の文字列（null なら null）
     */
    public static String format(Timestamp ts) { return ts == null ? null : ISO.format(ts.toLocalDateTime()); }

    /**
     * @param t 日時（null 可）
     * @return yyyy-MM-ddTHH:mm:ss 形式の文字列（null なら null）
     */
    public static String format(LocalDateTime t) { return t == null ? null : ISO.format(t); }
}
