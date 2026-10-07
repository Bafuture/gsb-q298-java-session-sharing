package com.example.gsb.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 需求 4：同一会话在不同节点并发更新，按版本号检测冲突，
 * 验证“拒绝后者”与“按时间取新”两种策略。
 */
class ConflictStrategyTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void rejectStrategyRejectsTheLaterUpdate() {
        SessionCluster cluster = new SessionCluster(new MutableClock(T0));
        SessionNode nodeA = cluster.scaleOut(NodeMode.NON_STICKY, ConflictStrategy.REJECT_CONFLICT);
        SessionNode nodeB = cluster.scaleOut(NodeMode.NON_STICKY, ConflictStrategy.REJECT_CONFLICT);
        nodeA.createSession("s1", Map.of("data", "v0"), Duration.ofMinutes(10));

        // 两节点都基于版本 1 并发更新
        nodeA.updateSession("s1", 1, Map.of("data", "from-A"));
        assertThatThrownBy(() -> nodeB.updateSession("s1", 1, Map.of("data", "from-B")))
                .isInstanceOf(VersionConflictException.class);

        Session stored = nodeA.read("s1").session();
        assertThat(stored.version()).isEqualTo(2);
        assertThat(stored.attributes()).containsEntry("data", "from-A");
        assertThat(cluster.stats().versionConflicts()).isEqualTo(1);
    }

    @Test
    void lastWriteWinsStrategyKeepsTheNewerTimestamp() {
        MutableClock clock = new MutableClock(T0);
        SessionCluster cluster = new SessionCluster(clock);
        SessionNode nodeA = cluster.scaleOut(NodeMode.NON_STICKY, ConflictStrategy.LAST_WRITE_WINS);
        SessionNode nodeB = cluster.scaleOut(NodeMode.NON_STICKY, ConflictStrategy.LAST_WRITE_WINS);
        nodeA.createSession("s1", Map.of("data", "v0"), Duration.ofMinutes(10));

        clock.advance(Duration.ofSeconds(1));
        nodeA.updateSession("s1", 1, Map.of("data", "from-A"));

        clock.advance(Duration.ofSeconds(10));
        nodeB.updateSession("s1", 1, Map.of("data", "from-B"));

        Session stored = nodeA.read("s1").session();
        assertThat(stored.version()).isEqualTo(3);
        assertThat(stored.attributes()).containsEntry("data", "from-B");
        assertThat(cluster.stats().versionConflicts()).isEqualTo(1);
    }

    @Test
    void lastWriteWinsStrategyDiscardsTheOlderTimestamp() {
        MutableClock clock = new MutableClock(T0);
        SessionCluster cluster = new SessionCluster(clock);
        SessionNode nodeA = cluster.scaleOut(NodeMode.NON_STICKY, ConflictStrategy.LAST_WRITE_WINS);
        SessionNode nodeB = cluster.scaleOut(NodeMode.NON_STICKY, ConflictStrategy.LAST_WRITE_WINS);
        nodeA.createSession("s1", Map.of("data", "v0"), Duration.ofMinutes(10));

        // nodeB 在较晚时间先更新（版本 2）
        clock.advance(Duration.ofSeconds(10));
        nodeB.updateSession("s1", 1, Map.of("data", "from-B"));

        // nodeA 的并发更新携带更早的时间戳，冲突仲裁时被丢弃
        clock.set(T0.plusSeconds(1));
        nodeA.updateSession("s1", 1, Map.of("data", "from-A"));

        Session stored = nodeA.read("s1").session();
        assertThat(stored.version()).isEqualTo(2);
        assertThat(stored.attributes()).containsEntry("data", "from-B");
        assertThat(cluster.stats().versionConflicts()).isEqualTo(1);
    }

    @Test
    void afterConflictAWriterCanRetryWithTheLatestVersion() {
        SessionCluster cluster = new SessionCluster(new MutableClock(T0));
        SessionNode nodeA = cluster.scaleOut(NodeMode.NON_STICKY, ConflictStrategy.REJECT_CONFLICT);
        SessionNode nodeB = cluster.scaleOut(NodeMode.NON_STICKY, ConflictStrategy.REJECT_CONFLICT);
        nodeA.createSession("s1", Map.of("data", "v0"), Duration.ofMinutes(10));

        nodeA.updateSession("s1", 1, Map.of("data", "from-A"));
        try {
            nodeB.updateSession("s1", 1, Map.of("data", "from-B"));
        } catch (VersionConflictException expected) {
            long latest = nodeB.read("s1").session().version();
            Session retried = nodeB.updateSession("s1", latest, Map.of("data", "from-B-retry"));
            assertThat(retried.version()).isEqualTo(3);
        }
        assertThat(nodeA.read("s1").session().attributes())
                .containsEntry("data", "from-B-retry");
    }
}
