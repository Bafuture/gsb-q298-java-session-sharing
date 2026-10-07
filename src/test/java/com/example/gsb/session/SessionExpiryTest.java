package com.example.gsb.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 需求 6：会话过期清理，清理后读取返回明确的不存在结果。 */
class SessionExpiryTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private MutableClock clock;
    private SessionCluster cluster;
    private SessionNode node;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        cluster = new SessionCluster(clock);
        node = cluster.scaleOut(NodeMode.NON_STICKY, ConflictStrategy.REJECT_CONFLICT);
    }

    @Test
    void sweepRemovesExpiredSessionsAndReadReturnsNotFound() {
        node.createSession("short", Map.of(), Duration.ofSeconds(1));
        node.createSession("long", Map.of(), Duration.ofHours(1));

        clock.advance(Duration.ofSeconds(2));
        int removed = cluster.sweepExpiredSessions();

        assertThat(removed).isEqualTo(1);
        assertThat(node.read("short")).isEqualTo(new SessionLookup.NotFound("short"));
        assertThat(node.read("long")).isInstanceOf(SessionLookup.Found.class);
        assertThat(cluster.stats().expiredSessions()).isEqualTo(1);
        assertThat(cluster.stats().activeSessions()).isEqualTo(1);
    }

    @Test
    void readOfExpiredSessionIsNotFoundEvenBeforeSweep() {
        node.createSession("s1", Map.of(), Duration.ofMinutes(1));

        clock.advance(Duration.ofMinutes(2));
        SessionLookup lookup = node.read("s1");

        assertThat(lookup).isEqualTo(new SessionLookup.NotFound("s1"));
        assertThat(cluster.stats().expiredSessions()).isEqualTo(1);
    }

    @Test
    void sweepIsIdempotentWhenNothingExpired() {
        node.createSession("s1", Map.of(), Duration.ofHours(1));

        assertThat(cluster.sweepExpiredSessions()).isZero();
        assertThat(node.read("s1")).isInstanceOf(SessionLookup.Found.class);
    }

    @Test
    void updateOnExpiredSessionFailsAndDoesNotResurrectIt() {
        node.createSession("s1", Map.of("v", 1), Duration.ofSeconds(10));
        clock.advance(Duration.ofSeconds(20));

        catchThrowableOfType(() -> node.updateSession("s1", 1, Map.of("v", 2)),
                NoSuchSessionException.class);
        assertThat(node.read("s1")).isEqualTo(new SessionLookup.NotFound("s1"));
    }

    @Test
    void invalidateIsNotCountedAsExpiry() {
        node.createSession("s1", Map.of(), Duration.ofHours(1));
        node.invalidateSession("s1");

        assertThat(cluster.stats().expiredSessions()).isZero();
        assertThat(cluster.stats().activeSessions()).isZero();
    }
}
