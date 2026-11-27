package com.example.bank.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** テスト用の時計（日本時間）。set で任意の日時に進められる。パッケージをまたいで使う。 */
public final class TestClock extends Clock {
    /** 業務の基準のタイムゾーン（日本時間）。 */
    public static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private Instant now;

    /** @param local 開始日時（日本時間） */
    public TestClock(LocalDateTime local) { set(local); }

    /** @param local 新しい日時（日本時間） */
    public void set(LocalDateTime local) { now = local.atZone(JST).toInstant(); }

    @Override public ZoneId getZone() { return JST; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
}
