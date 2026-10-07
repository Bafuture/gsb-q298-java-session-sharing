package com.example.gsb.session;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/**
 * 会话集群：持有中心存储与全部在线节点，负责实例扩缩容与缓存一致性广播。
 */
public final class SessionCluster {

    private final SessionStore store;
    private final SessionMetrics metrics;
    private final List<SessionNode> nodes = new CopyOnWriteArrayList<>();

    public SessionCluster(ConflictStrategy conflictStrategy, LongSupplier clockMillis) {
        this.metrics = new SessionMetrics();
        this.store = new SessionStore(conflictStrategy, clockMillis, metrics);
    }

    public SessionCluster(ConflictStrategy conflictStrategy) {
        this(conflictStrategy, System::currentTimeMillis);
    }

    public SessionStore getStore() {
        return store;
    }

    public SessionMetrics getMetrics() {
        return metrics;
    }

    long now() {
        return store.now();
    }

    /** 扩容：新实例加入并从中心存储同步全部会话。 */
    public SessionNode addNode(String nodeId, SessionMode mode) {
        SessionNode node = new SessionNode(nodeId, mode, this);
        node.syncFromStore();
        nodes.add(node);
        metrics.recordScaleOut();
        return node;
    }

    /** 缩容：实例离开前回收本地会话到中心存储，其上会话不丢失。 */
    public void removeNode(SessionNode node) {
        node.flushToStore();
        nodes.remove(node);
        metrics.recordScaleIn();
    }

    public List<SessionNode> getNodes() {
        return List.copyOf(nodes);
    }

    /** 当前存活会话数。 */
    public int activeSessionCount() {
        return store.activeCount();
    }

    /** 主动清理所有已过期会话，返回清理数量。 */
    public int cleanupExpired() {
        return store.cleanupExpired();
    }

    Session create(String sessionId, Map<String, String> attributes, long ttlMillis, String ownerNodeId) {
        Session session = store.create(sessionId, attributes, ttlMillis, ownerNodeId);
        broadcastRefresh(session);
        return session;
    }

    UpdateResult update(String sessionId, long expectedVersion, Map<String, String> newAttributes) {
        UpdateResult result = store.update(sessionId, expectedVersion, newAttributes);
        broadcastRefresh(result.session());
        return result;
    }

    boolean invalidate(String sessionId) {
        boolean removed = store.invalidate(sessionId);
        broadcastEvict(sessionId);
        return removed;
    }

    private void broadcastRefresh(Session session) {
        for (SessionNode node : nodes) {
            node.refreshCache(session);
        }
    }

    private void broadcastEvict(String sessionId) {
        for (SessionNode node : nodes) {
            node.evictCache(sessionId);
        }
    }
}
