package com.example.gsb.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 需求 5：扩容后新实例能读全部会话；缩容时其上的会话不丢失。 */
class ScaleInOutTest {

    @Test
    void newlyScaledOutNodeReadsAllExistingSessions() {
        SessionCluster cluster = new SessionCluster(Clock.systemUTC());
        SessionNode nodeA = cluster.scaleOut(NodeMode.NON_STICKY, ConflictStrategy.REJECT_CONFLICT);

        List<String> ids = List.of("s1", "s2", "s3");
        ids.forEach(id -> nodeA.createSession(id, Map.of("id", id), Duration.ofMinutes(10)));

        SessionNode nodeB = cluster.scaleOut(NodeMode.NON_STICKY, ConflictStrategy.REJECT_CONFLICT);

        for (String id : ids) {
            SessionLookup lookup = nodeB.read(id);
            assertThat(lookup).isInstanceOf(SessionLookup.Found.class);
            assertThat(lookup.session().attributes()).containsEntry("id", id);
        }
        assertThat(cluster.nodeCount()).isEqualTo(2);
        assertThat(cluster.stats().scaleOuts()).isEqualTo(2);
    }

    @Test
    void scalingInANodeDoesNotLoseItsSessions() {
        SessionCluster cluster = new SessionCluster(Clock.systemUTC());
        SessionNode nodeA = cluster.scaleOut(NodeMode.STICKY, ConflictStrategy.REJECT_CONFLICT);
        SessionNode nodeB = cluster.scaleOut(NodeMode.NON_STICKY, ConflictStrategy.REJECT_CONFLICT);

        nodeA.createSession("s1", Map.of("user", "alice"), Duration.ofMinutes(10));
        nodeA.createSession("s2", Map.of("user", "bob"), Duration.ofMinutes(10));
        nodeA.read("s1");
        nodeA.read("s2");
        assertThat(nodeA.localCacheSize()).isEqualTo(2);

        assertThat(cluster.scaleIn(nodeA.nodeId())).isTrue();
        assertThat(cluster.nodeCount()).isEqualTo(1);

        for (String id : List.of("s1", "s2")) {
            assertThat(nodeB.read(id)).isInstanceOf(SessionLookup.Found.class);
            assertThat(nodeB.read(id).session().attributes().get("user"))
                    .isEqualTo(id.equals("s1") ? "alice" : "bob");
        }
        assertThat(cluster.stats().scaleIns()).isEqualTo(1);
    }

    @Test
    void sessionWrittenBeforeAndAfterScaleOutIsVisibleEverywhere() {
        SessionCluster cluster = new SessionCluster(Clock.systemUTC());
        SessionNode nodeA = cluster.scaleOut(NodeMode.NON_STICKY, ConflictStrategy.REJECT_CONFLICT);
        nodeA.createSession("old", Map.of(), Duration.ofMinutes(10));

        SessionNode nodeB = cluster.scaleOut(NodeMode.NON_STICKY, ConflictStrategy.REJECT_CONFLICT);
        nodeB.createSession("new", Map.of(), Duration.ofMinutes(10));

        assertThat(nodeA.read("new")).isInstanceOf(SessionLookup.Found.class);
        assertThat(nodeB.read("old")).isInstanceOf(SessionLookup.Found.class);
    }

    @Test
    void scalingInUnknownNodeReturnsFalse() {
        SessionCluster cluster = new SessionCluster(Clock.systemUTC());
        assertThat(cluster.scaleIn("ghost")).isFalse();
    }
}
