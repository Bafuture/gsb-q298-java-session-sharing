package com.example.gsb.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 需求 7：统计会话数、版本冲突次数、扩缩容次数、过期清理数。
 */
class ClusterStatsTest {

    @Test
    void statsAggregateAllCounters() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        SessionCluster cluster = new SessionCluster(clock);
        SessionNode nodeA = cluster.scaleOut(NodeMode.NON_STICKY, ConflictStrategy.REJECT_CONFLICT);
        SessionNode nodeB = cluster.scaleOut(NodeMode.NON_STICKY, ConflictStrategy.REJECT_CONFLICT);
        SessionNode nodeC = cluster.scaleOut(NodeMode.NON_STICKY, ConflictStrategy.REJECT_CONFLICT);
        cluster.scaleIn(nodeC.nodeId());

        nodeA.createSession("s1", Map.of(), Duration.ofMinutes(30));
        nodeA.createSession("s2", Map.of(), Duration.ofSeconds(1));
        nodeB.createSession("s3", Map.of(), Duration.ofMinutes(30));

        // 一次版本冲突
        nodeA.updateSession("s1", 1, Map.of("a", 1));
        catchThrowableOfType(() -> nodeB.updateSession("s1", 1, Map.of("b", 2)),
                VersionConflictException.class);

        // s2 过期被清理
        clock.advance(Duration.ofSeconds(2));
        int swept = cluster.sweepExpiredSessions();
        assertThat(swept).isEqualTo(1);

        SessionStats stats = cluster.stats();
        assertThat(stats.activeSessions()).isEqualTo(2);
        assertThat(stats.versionConflicts()).isEqualTo(1);
        assertThat(stats.scaleOuts()).isEqualTo(3);
        assertThat(stats.scaleIns()).isEqualTo(1);
        assertThat(stats.expiredSessions()).isEqualTo(1);
    }

    @Test
    void initialStatsAreAllZero() {
        SessionCluster cluster = new SessionCluster(new MutableClock(Instant.now()));

        SessionStats stats = cluster.stats();
        assertThat(stats.activeSessions()).isZero();
        assertThat(stats.versionConflicts()).isZero();
        assertThat(stats.scaleOuts()).isZero();
        assertThat(stats.scaleIns()).isZero();
        assertThat(stats.expiredSessions()).isZero();
    }
}
