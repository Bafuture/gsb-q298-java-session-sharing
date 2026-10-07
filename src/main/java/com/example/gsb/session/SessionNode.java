package com.example.gsb.session;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务实例（节点）。所有写操作都直达共享存储；
 * 粘性模式下节点额外维护本地缓存以加速读，非粘性模式每次读都走共享存储。
 */
public final class SessionNode {

    private final String nodeId;
    private final NodeMode mode;
    private final ConflictStrategy conflictStrategy;
    private final InMemorySessionStore store;
    private final Clock clock;
    private final ConcurrentHashMap<String, Session> localCache = new ConcurrentHashMap<>();

    SessionNode(String nodeId, NodeMode mode, ConflictStrategy conflictStrategy,
                InMemorySessionStore store, Clock clock) {
        this.nodeId = Objects.requireNonNull(nodeId);
        this.mode = Objects.requireNonNull(mode);
        this.conflictStrategy = Objects.requireNonNull(conflictStrategy);
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    public String nodeId() {
        return nodeId;
    }

    public NodeMode mode() {
        return mode;
    }

    public Session createSession(String sessionId, Map<String, Object> attributes, Duration ttl) {
        Session created = store.create(sessionId, attributes, ttl);
        if (mode == NodeMode.STICKY) {
            localCache.put(sessionId, created);
        }
        return created;
    }

    /**
     * 读取会话。粘性模式优先命中本地缓存（可能陈旧，见 {@link NodeMode#STICKY}），
     * 未命中则回源共享存储并填充缓存；非粘性模式始终读共享存储的最新版本。
     */
    public SessionLookup read(String sessionId) {
        if (mode == NodeMode.STICKY) {
            Session cached = localCache.get(sessionId);
            if (cached != null) {
                if (!cached.isExpired(clock.instant())) {
                    return new SessionLookup.Found(cached);
                }
                localCache.remove(sessionId, cached);
            }
        }
        SessionLookup lookup = store.find(sessionId);
        if (mode == NodeMode.STICKY && lookup instanceof SessionLookup.Found f) {
            localCache.put(sessionId, f.session());
        }
        return lookup;
    }

    /**
     * 按期望版本号更新；冲突时按本节点配置的 {@link ConflictStrategy} 处理。
     */
    public Session updateSession(String sessionId, long expectedVersion,
                                 Map<String, Object> attributes) {
        Session updated = store.update(sessionId, expectedVersion, attributes, conflictStrategy);
        if (mode == NodeMode.STICKY) {
            localCache.put(sessionId, updated);
        }
        return updated;
    }

    public boolean invalidateSession(String sessionId) {
        localCache.remove(sessionId);
        return store.invalidate(sessionId);
    }

    /** 节点下线（缩容）时调用：清空本地缓存，数据仍在共享存储中，不丢会话。 */
    void evictLocalCache() {
        localCache.clear();
    }

    int localCacheSize() {
        return localCache.size();
    }
}
