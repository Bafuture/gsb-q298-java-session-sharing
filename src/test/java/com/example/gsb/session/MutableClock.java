package com.example.gsb.session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 测试用可手动推进的时钟。 */
final class MutableClock extends Clock {

    private Instant instant;

    MutableClock(Instant start) {
        this.instant = start;
    }

    void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    void set(Instant newInstant) {
        instant = newInstant;
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
