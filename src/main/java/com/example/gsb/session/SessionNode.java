package com.example.gsb.session;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 服务实例节点。
 *
 * <p>粘性模式（STICKY）：会话归属创建它的节点（owner），该节点是唯一写者，
 * 归属会话可直接读本地缓存；读其他节点的会话或非粘性模式下都走中心存储。</p>
 *
 * <p>非粘性模式（NON_STICKY）：每次读写都经中心存储做版本 CAS，
 * 保证任意节点读到的都是最新的已提交完整版本（强一致）。</p>
 */
public final class SessionNode {

    private final String nodeId;
    private final SessionMode mode;
    private final SessionCluster cluster;
    private final ConcurrentMap<String, Session> localCache = new ConcurrentHashMap<>();

    SessionNode(String nodeId, SessionMode mode, SessionCluster cluster) {
        this.nodeId = nodeId;
        this.mode = mode;
        this.cluster = cluster;
    }

    public String getNodeId() {
        return nodeId;
    }

    public SessionMode getMode() {
        return mode;
    }

    /** 创建会话。粘性模式下会话归属本节点。 */
    public Session createSession(String sessionId, Map<String, String> attributes, long ttlMillis) {
        String owner = mode == SessionMode.STICKY ? nodeId : null;
        return cluster.create(sessionId, attributes, ttlMillis, owner);
    }

    public Session createSession(String sessionId, Map<String, String> attributes) {
        return createSession(sessionId, attributes, Session.NEVER_EXPIRES);
    }

    /** 读取会话，返回不可变完整版本；不存在或已过期返回 Optional.empty()。 */
    public Optional<Session> getSession(String sessionId) {
        if (mode == SessionMode.STICKY) {
            Session local = localCache.get(sessionId);
            if (local != null && nodeId.equals(local.getOwnerNodeId())
                    && !local.isExpired(cluster.now())) {
                return Optional.of(local);
            }
        }
        Optional<Session> fromStore = cluster.getStore().get(sessionId);
        fromStore.ifPresent(session -> localCache.put(sessionId, session));
        return fromStore;
    }

    /** 按版本号更新会话。 */
    public UpdateResult updateSession(String sessionId, long expectedVersion, Map<String, String> newAttributes) {
        return cluster.update(sessionId, expectedVersion, newAttributes);
    }

    /** 失效会话。 */
    public boolean invalidateSession(String sessionId) {
        localCache.remove(sessionId);
        return cluster.invalidate(sessionId);
    }

    /** 扩容加入时：从中心存储同步全部会话（新实例立刻能读全部会话）。 */
    void syncFromStore() {
        localCache.clear();
        for (Session session : cluster.getStore().snapshot()) {
            localCache.put(session.getId(), session);
        }
    }

    /** 缩容离开时：把本地会话回收到中心存储（其上的会话不丢失）。 */
    void flushToStore() {
        for (Session session : localCache.values()) {
            cluster.getStore().restore(session);
        }
        localCache.clear();
    }

    void refreshCache(Session session) {
        localCache.put(session.getId(), session);
    }

    void evictCache(String sessionId) {
        localCache.remove(sessionId);
    }
}
