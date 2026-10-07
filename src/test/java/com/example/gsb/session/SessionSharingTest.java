package com.example.gsb.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SessionSharingTest {

    private final AtomicLong clock = new AtomicLong(1_000L);

    private SessionCluster newCluster(ConflictStrategy strategy) {
        return new SessionCluster(strategy, clock::get);
    }

    @Test
    @DisplayName("创建、读取、更新、失效，会话带递增版本号")
    void createReadUpdateInvalidate() {
        SessionCluster cluster = newCluster(ConflictStrategy.REJECT_STALE);
        SessionNode nodeA = cluster.addNode("A", SessionMode.NON_STICKY);
        SessionNode nodeB = cluster.addNode("B", SessionMode.NON_STICKY);

        Session created = nodeA.createSession("s1", Map.of("user", "alice"));
        assertThat(created.getVersion()).isEqualTo(1);

        Optional<Session> readByB = nodeB.getSession("s1");
        assertThat(readByB).isPresent();
        assertThat(readByB.get().getAttributes()).containsEntry("user", "alice");

        UpdateResult updated = nodeB.updateSession("s1", 1, Map.of("user", "alice", "cart", "3"));
        assertThat(updated.applied()).isTrue();
        assertThat(updated.session().getVersion()).isEqualTo(2);

        Optional<Session> readByA = nodeA.getSession("s1");
        assertThat(readByA).isPresent();
        assertThat(readByA.get().getVersion()).isEqualTo(2);
        assertThat(readByA.get().getAttributes()).containsEntry("cart", "3");

        assertThat(nodeA.invalidateSession("s1")).isTrue();
        assertThat(nodeA.getSession("s1")).isEmpty();
        assertThat(nodeB.getSession("s1")).isEmpty();
    }

    @Test
    @DisplayName("多节点共享：任意节点都能读到会话")
    void anyNodeCanRead() {
        SessionCluster cluster = newCluster(ConflictStrategy.REJECT_STALE);
        SessionNode nodeA = cluster.addNode("A", SessionMode.NON_STICKY);
        SessionNode nodeB = cluster.addNode("B", SessionMode.STICKY);
        SessionNode nodeC = cluster.addNode("C", SessionMode.NON_STICKY);

        nodeA.createSession("shared", Map.of("k", "v"));

        for (SessionNode node : List.of(nodeA, nodeB, nodeC)) {
            assertThat(node.getSession("shared")).isPresent();
            assertThat(node.getSession("shared").get().getAttributes()).containsEntry("k", "v");
        }
    }

    @Test
    @DisplayName("并发更新期间读到的始终是某个完整版本")
    void readsAlwaysSeeCompleteVersion() throws Exception {
        SessionCluster cluster = newCluster(ConflictStrategy.REJECT_STALE);
        SessionNode writer = cluster.addNode("writer", SessionMode.NON_STICKY);
        SessionNode reader = cluster.addNode("reader", SessionMode.NON_STICKY);
        writer.createSession("s", Map.of("k1", "0", "k2", "0"));

        int iterations = 500;
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> writeTask = pool.submit(() -> {
                await(start);
                for (int i = 1; i <= iterations; i++) {
                    String value = String.valueOf(i);
                    // 每个版本里 k1 与 k2 必须同时被更新为相同值
                    writer.updateSession("s", i, Map.of("k1", value, "k2", value));
                }
            });
            Future<?> readTask = pool.submit(() -> {
                await(start);
                for (int i = 0; i < iterations * 2; i++) {
                    Optional<Session> snapshot = reader.getSession("s");
                    assertThat(snapshot).isPresent();
                    Map<String, String> attrs = snapshot.get().getAttributes();
                    // 读到的必须是完整版本：k1 与 k2 来自同一次更新
                    assertThat(attrs.get("k1"))
                            .as("版本 v%s 读到不完整状态", snapshot.get().getVersion())
                            .isEqualTo(attrs.get("k2"));
                }
            });
            start.countDown();
            writeTask.get(30, TimeUnit.SECONDS);
            readTask.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发冲突 - REJECT_STALE：拒绝版本落后的更新")
    void conflictRejectStale() {
        SessionCluster cluster = newCluster(ConflictStrategy.REJECT_STALE);
        SessionNode nodeA = cluster.addNode("A", SessionMode.NON_STICKY);
        SessionNode nodeB = cluster.addNode("B", SessionMode.NON_STICKY);

        nodeA.createSession("s", Map.of("v", "init"));
        // 两个节点都基于 v1 并发更新
        UpdateResult first = nodeA.updateSession("s", 1, Map.of("v", "A"));
        assertThat(first.applied()).isTrue();

        assertThatThrownBy(() -> nodeB.updateSession("s", 1, Map.of("v", "B")))
                .isInstanceOf(VersionConflictException.class)
                .hasMessageContaining("s");

        // 被拒绝的写入没有生效
        assertThat(nodeA.getSession("s")).isPresent();
        assertThat(nodeA.getSession("s").get().getAttributes()).containsEntry("v", "A");
        assertThat(cluster.getMetrics().getVersionConflicts()).isEqualTo(1);
    }

    @Test
    @DisplayName("并发冲突 - LAST_WRITE_WINS：按时间取新，旧写被丢弃")
    void conflictLastWriteWins() {
        SessionCluster cluster = newCluster(ConflictStrategy.LAST_WRITE_WINS);
        SessionNode nodeA = cluster.addNode("A", SessionMode.NON_STICKY);
        SessionNode nodeB = cluster.addNode("B", SessionMode.NON_STICKY);

        clock.set(100);
        nodeA.createSession("s", Map.of("v", "init"));

        clock.set(200);
        UpdateResult fromA = nodeA.updateSession("s", 1, Map.of("v", "A@200"));
        assertThat(fromA.applied()).isTrue();

        // B 持有过期的 v1，且写入时间(150)落后于存储版本时间(200) -> 被丢弃
        clock.set(150);
        UpdateResult staleWrite = nodeB.updateSession("s", 1, Map.of("v", "B@150"));
        assertThat(staleWrite.conflictDetected()).isTrue();
        assertThat(staleWrite.applied()).isFalse();
        assertThat(nodeA.getSession("s").get().getAttributes()).containsEntry("v", "A@200");

        // B 再次以过期版本写入，但时间(300)更新 -> 覆盖生效
        clock.set(300);
        UpdateResult newerWrite = nodeB.updateSession("s", 1, Map.of("v", "B@300"));
        assertThat(newerWrite.conflictDetected()).isTrue();
        assertThat(newerWrite.applied()).isTrue();
        assertThat(nodeA.getSession("s").get().getAttributes()).containsEntry("v", "B@300");
        assertThat(nodeA.getSession("s").get().getVersion()).isEqualTo(3);

        assertThat(cluster.getMetrics().getVersionConflicts()).isEqualTo(2);
    }

    @Test
    @DisplayName("扩容：新实例加入后能读取全部会话")
    void scaleOutNewNodeSeesAllSessions() {
        SessionCluster cluster = newCluster(ConflictStrategy.REJECT_STALE);
        SessionNode nodeA = cluster.addNode("A", SessionMode.NON_STICKY);
        nodeA.createSession("s1", Map.of("n", "1"));
        nodeA.createSession("s2", Map.of("n", "2"));
        nodeA.createSession("s3", Map.of("n", "3"));

        SessionNode nodeB = cluster.addNode("B", SessionMode.NON_STICKY);

        for (String id : List.of("s1", "s2", "s3")) {
            assertThat(nodeB.getSession(id)).isPresent();
        }
        assertThat(cluster.getMetrics().getScaleOuts()).isEqualTo(2);
    }

    @Test
    @DisplayName("缩容：实例下线后其上的会话不丢失")
    void scaleInDoesNotLoseSessions() {
        SessionCluster cluster = newCluster(ConflictStrategy.REJECT_STALE);
        SessionNode nodeA = cluster.addNode("A", SessionMode.NON_STICKY);
        SessionNode nodeB = cluster.addNode("B", SessionMode.STICKY);

        // 粘性会话归属 B，由 B 本地持有
        nodeB.createSession("sticky-1", Map.of("owner", "B"));
        nodeB.createSession("sticky-2", Map.of("owner", "B"));
        nodeA.createSession("plain", Map.of("owner", "A"));

        cluster.removeNode(nodeB);

        assertThat(nodeA.getSession("sticky-1")).isPresent();
        assertThat(nodeA.getSession("sticky-2")).isPresent();
        assertThat(nodeA.getSession("plain")).isPresent();
        assertThat(cluster.activeSessionCount()).isEqualTo(3);
        assertThat(cluster.getMetrics().getScaleIns()).isEqualTo(1);
    }

    @Test
    @DisplayName("过期清理：过期会话被清理，读取返回明确的不存在结果")
    void expirationCleanup() {
        SessionCluster cluster = newCluster(ConflictStrategy.REJECT_STALE);
        SessionNode node = cluster.addNode("A", SessionMode.NON_STICKY);

        clock.set(0);
        node.createSession("short-1", Map.of("k", "1"), 1_000);
        node.createSession("short-2", Map.of("k", "2"), 1_000);
        node.createSession("long", Map.of("k", "3"), 60_000);

        clock.set(500);
        assertThat(node.getSession("short-1")).isPresent();

        clock.set(1_500);
        int cleaned = cluster.cleanupExpired();
        assertThat(cleaned).isEqualTo(2);

        assertThat(node.getSession("short-1")).isEmpty();
        assertThat(node.getSession("short-2")).isEmpty();
        assertThat(node.getSession("long")).isPresent();
        assertThat(cluster.activeSessionCount()).isEqualTo(1);
        assertThat(cluster.getMetrics().getExpiredRemovals()).isEqualTo(2);

        // 惰性过期：读取已过期但未扫描的会话同样返回不存在并计数
        clock.set(61_500);
        assertThat(node.getSession("long")).isEmpty();
        assertThat(cluster.getMetrics().getExpiredRemovals()).isEqualTo(3);
    }

    @Test
    @DisplayName("统计：会话数、版本冲突、扩缩容次数、过期清理数")
    void statistics() {
        SessionCluster cluster = newCluster(ConflictStrategy.REJECT_STALE);
        SessionNode nodeA = cluster.addNode("A", SessionMode.NON_STICKY);

        clock.set(0);
        nodeA.createSession("s1", Map.of("k", "1"));
        nodeA.createSession("s2", Map.of("k", "2"), 100);
        assertThat(cluster.activeSessionCount()).isEqualTo(2);

        nodeA.updateSession("s1", 1, Map.of("k", "1'"));
        assertThatThrownBy(() -> nodeA.updateSession("s1", 1, Map.of("k", "x")))
                .isInstanceOf(VersionConflictException.class);

        SessionNode nodeB = cluster.addNode("B", SessionMode.NON_STICKY);
        cluster.removeNode(nodeB);

        clock.set(1_000);
        cluster.cleanupExpired();

        SessionMetrics metrics = cluster.getMetrics();
        assertThat(cluster.activeSessionCount()).isEqualTo(1);
        assertThat(metrics.getVersionConflicts()).isEqualTo(1);
        assertThat(metrics.getScaleOuts()).isEqualTo(2);
        assertThat(metrics.getScaleIns()).isEqualTo(1);
        assertThat(metrics.getExpiredRemovals()).isEqualTo(1);
    }

    @Test
    @DisplayName("粘性模式：归属节点读本地缓存，其他节点经中心存储读到一致内容")
    void stickyModeConsistency() {
        SessionCluster cluster = newCluster(ConflictStrategy.REJECT_STALE);
        SessionNode owner = cluster.addNode("owner", SessionMode.STICKY);
        SessionNode other = cluster.addNode("other", SessionMode.NON_STICKY);

        Session created = owner.createSession("sticky", Map.of("k", "v1"));
        assertThat(created.getOwnerNodeId()).isEqualTo("owner");

        owner.updateSession("sticky", 1, Map.of("k", "v2"));

        assertThat(owner.getSession("sticky").get().getAttributes()).containsEntry("k", "v2");
        assertThat(other.getSession("sticky").get().getAttributes()).containsEntry("k", "v2");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
