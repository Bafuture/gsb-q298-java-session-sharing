package com.example.gsb.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * 中心会话存储（模拟多节点共享的后端存储，如 Redis / 分布式 KV）。
 *
 * <p>所有写操作都在 {@link ConcurrentMap#compute} 中原子完成，配合不可变的 {@link Session}
 * 快照，保证任意节点读到的一定是某个完整版本。更新采用版本号 CAS：调用方带上自己读到的
 * 版本号，版本不一致即发生冲突，并按 {@link ConflictStrategy} 处理。</p>
 */
public final class SessionStore {

    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final LongSupplier clockMillis;
    private final ConflictStrategy conflictStrategy;
    private final SessionMetrics metrics;

    public SessionStore(ConflictStrategy conflictStrategy, LongSupplier clockMillis, SessionMetrics metrics) {
        this.conflictStrategy = conflictStrategy;
        this.clockMillis = clockMillis;
        this.metrics = metrics;
    }

    long now() {
        return clockMillis.getAsLong();
    }

    /** 创建会话，初始版本号为 1。 */
    public Session create(String sessionId, Map<String, String> attributes, long ttlMillis, String ownerNodeId) {
        long now = now();
        Session[] box = new Session[1];
        sessions.compute(sessionId, (id, current) -> {
            if (current != null && !current.isExpired(now)) {
                throw new SessionAlreadyExistsException(id);
            }
            if (current != null) {
                metrics.recordExpiredRemoval();
            }
            box[0] = new Session(id, 1, attributes, now, now, ttlMillis, ownerNodeId);
            return box[0];
        });
        return box[0];
    }

    /** 读取会话。不存在或已过期返回 Optional.empty()（明确的“不存在”结果），过期会惰性清理。 */
    public Optional<Session> get(String sessionId) {
        long now = now();
        Session current = sessions.get(sessionId);
        if (current == null) {
            return Optional.empty();
        }
        if (current.isExpired(now)) {
            if (sessions.remove(sessionId, current)) {
                metrics.recordExpiredRemoval();
            }
            return Optional.empty();
        }
        return Optional.of(current);
    }

    /**
     * 按版本号 CAS 更新会话。
     *
     * @param expectedVersion 调用方读取时看到的版本号
     * @param newAttributes   新的完整属性集合
     */
    public UpdateResult update(String sessionId, long expectedVersion, Map<String, String> newAttributes) {
        long now = now();
        UpdateResult[] box = new UpdateResult[1];
        sessions.compute(sessionId, (id, current) -> {
            if (current == null || current.isExpired(now)) {
                if (current != null) {
                    metrics.recordExpiredRemoval();
                }
                throw new SessionNotFoundException(id);
            }
            if (current.getVersion() != expectedVersion) {
                metrics.recordVersionConflict();
                if (conflictStrategy == ConflictStrategy.REJECT_STALE) {
                    throw new VersionConflictException(id, expectedVersion, current.getVersion());
                }
                // LAST_WRITE_WINS：时间戳更旧的写入被丢弃，保持存储中更新的版本
                if (now <= current.getLastModifiedAtMillis()) {
                    box[0] = new UpdateResult(current, true, false);
                    return current;
                }
            }
            Session next = current.withAttributes(newAttributes, now);
            box[0] = new UpdateResult(next, current.getVersion() != expectedVersion, true);
            return next;
        });
        return box[0];
    }

    /** 失效（删除）会话。 */
    public boolean invalidate(String sessionId) {
        return sessions.remove(sessionId) != null;
    }

    /** 扫描并清理全部已过期会话，返回清理数量。 */
    public int cleanupExpired() {
        long now = now();
        int removed = 0;
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            Session candidate = entry.getValue();
            if (candidate.isExpired(now) && sessions.remove(entry.getKey(), candidate)) {
                metrics.recordExpiredRemoval();
                removed++;
            }
        }
        return removed;
    }

    /** 当前存活（未过期）会话数。 */
    public int activeCount() {
        long now = now();
        int count = 0;
        for (Session session : sessions.values()) {
            if (!session.isExpired(now)) {
                count++;
            }
        }
        return count;
    }

    /** 返回全部存活会话的快照（实例扩容时新节点据此同步）。 */
    public List<Session> snapshot() {
        long now = now();
        List<Session> result = new ArrayList<>();
        for (Session session : sessions.values()) {
            if (!session.isExpired(now)) {
                result.add(session);
            }
        }
        return result;
    }

    /**
     * 实例缩容时把节点本地会话回收进中心存储。版本更新或时间戳更新的副本才覆盖，
     * 避免落后的本地副本冲掉其他节点已经提交的新版本。
     */
    public void restore(Session local) {
        long now = now();
        sessions.merge(local.getId(), local, (current, incoming) -> {
            if (current.isExpired(now)) {
                return incoming;
            }
            if (incoming.getVersion() > current.getVersion()) {
                return incoming;
            }
            if (incoming.getVersion() == current.getVersion()
                    && incoming.getLastModifiedAtMillis() > current.getLastModifiedAtMillis()) {
                return incoming;
            }
            return current;
        });
    }
}
