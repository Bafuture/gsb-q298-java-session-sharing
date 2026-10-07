package com.example.gsb.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 需求 3：粘性 / 非粘性两种模式的一致性语义。
 * 非粘性：每次读共享存储，始终看到最新完整版本；
 * 粘性：读本地缓存，依赖负载均衡把同一会话固定路由到本节点，
 *      别的节点的更新在本节点刷新/失效缓存前是看不到的。
 */
class StickyVsNonStickyTest {

    @Test
    void nonStickyNodesAlwaysReadTheLatestVersion() {
        SessionCluster cluster = new SessionCluster(Clock.systemUTC());
        SessionNode nodeA = cluster.scaleOut(NodeMode.NON_STICKY, ConflictStrategy.REJECT_CONFLICT);
        SessionNode nodeB = cluster.scaleOut(NodeMode.NON_STICKY, ConflictStrategy.REJECT_CONFLICT);
        nodeA.createSession("s1", Map.of("data", "v0"), Duration.ofMinutes(10));

        nodeA.read("s1");
        nodeB.updateSession("s1", 1, Map.of("data", "v1"));

        assertThat(nodeA.read("s1").session().attributes()).containsEntry("data", "v1");
        assertThat(nodeA.read("s1").session().version()).isEqualTo(2);
    }

    @Test
    void stickyNodeServesCachedVersionWhileRouteStaysOnIt() {
        SessionCluster cluster = new SessionCluster(Clock.systemUTC());
        SessionNode stickyA = cluster.scaleOut(NodeMode.STICKY, ConflictStrategy.REJECT_CONFLICT);
        SessionNode nodeB = cluster.scaleOut(NodeMode.NON_STICKY, ConflictStrategy.REJECT_CONFLICT);
        stickyA.createSession("s1", Map.of("data", "v0"), Duration.ofMinutes(10));

        // stickyA 读过一次后本地缓存了版本 1
        assertThat(stickyA.read("s1").session().version()).isEqualTo(1);
        assertThat(stickyA.localCacheSize()).isEqualTo(1);

        // 别的节点的更新对 stickyA 的本地缓存不可见（粘性路由下不应发生）
        nodeB.updateSession("s1", 1, Map.of("data", "v1"));
        assertThat(stickyA.read("s1").session().attributes()).containsEntry("data", "v0");

        // 非粘性节点始终看到最新版本
        assertThat(nodeB.read("s1").session().attributes()).containsEntry("data", "v1");

        // stickyA 自己写入后，缓存与共享存储同步前进
        stickyA.updateSession("s1", 2, Map.of("data", "v2"));
        assertThat(stickyA.read("s1").session().attributes()).containsEntry("data", "v2");
        assertThat(stickyA.read("s1").session().version()).isEqualTo(3);
    }

    @Test
    void stickyNodeLoadsFromSharedStoreOnCacheMiss() {
        SessionCluster cluster = new SessionCluster(Clock.systemUTC());
        SessionNode nodeA = cluster.scaleOut(NodeMode.NON_STICKY, ConflictStrategy.REJECT_CONFLICT);
        SessionNode stickyB = cluster.scaleOut(NodeMode.STICKY, ConflictStrategy.REJECT_CONFLICT);
        nodeA.createSession("s1", Map.of("data", "v0"), Duration.ofMinutes(10));

        assertThat(stickyB.read("s1").session().attributes()).containsEntry("data", "v0");
        assertThat(stickyB.localCacheSize()).isEqualTo(1);
    }

    @Test
    void stickyCacheDoesNotServeExpiredSessions() {
        MutableClock clock = new MutableClock(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        SessionCluster cluster = new SessionCluster(clock);
        SessionNode sticky = cluster.scaleOut(NodeMode.STICKY, ConflictStrategy.REJECT_CONFLICT);
        sticky.createSession("s1", Map.of(), Duration.ofMinutes(1));
        sticky.read("s1");

        clock.advance(Duration.ofMinutes(2));

        assertThat(sticky.read("s1")).isEqualTo(new SessionLookup.NotFound("s1"));
    }
}
