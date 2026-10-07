package com.example.gsb.session;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话集群：管理节点（服务实例）的扩容/缩容，并汇总统计。
 * 会话数据全部落在共享存储 {@link InMemorySessionStore} 中，
 * 因此扩容出的新实例立即可读全部会话，缩容下线实例也不会丢会话。
 */
public final class SessionCluster {

    private final InMemorySessionStore store;
    private final Clock clock;
    private final Map<String, SessionNode> nodes = new ConcurrentHashMap<>();
    private final AtomicInteger nodeSequence = new AtomicInteger();
    private final AtomicLong scaleOuts = new AtomicLong();
    private final AtomicLong scaleIns = new AtomicLong();

    public SessionCluster(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        this.store = new InMemorySessionStore(clock);
    }

    /** 扩容：加入一个新实例，返回该节点。 */
    public SessionNode scaleOut(NodeMode mode, ConflictStrategy conflictStrategy) {
        String nodeId = "node-" + nodeSequence.incrementAndGet();
        SessionNode node = new SessionNode(nodeId, mode, conflictStrategy, store, clock);
        nodes.put(nodeId, node);
        scaleOuts.incrementAndGet();
        return node;
    }

    /** 缩容：下线指定实例。其本地缓存被清空，会话数据保留在共享存储中。 */
    public boolean scaleIn(String nodeId) {
        SessionNode removed = nodes.remove(nodeId);
        if (removed == null) {
            return false;
        }
        removed.evictLocalCache();
        scaleIns.incrementAndGet();
        return true;
    }

    public SessionNode node(String nodeId) {
        SessionNode node = nodes.get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("no such node: " + nodeId);
        }
        return node;
    }

    public List<SessionNode> nodes() {
        return List.copyOf(nodes.values());
    }

    public int nodeCount() {
        return nodes.size();
    }

    /** 触发一次过期会话清理，返回清理条数。 */
    public int sweepExpiredSessions() {
        return store.sweepExpired();
    }

    public SessionStats stats() {
        return new SessionStats(
                store.activeSessionCount(),
                store.versionConflictCount(),
                scaleOuts.get(),
                scaleIns.get(),
                store.expiredSessionCount());
    }
}
