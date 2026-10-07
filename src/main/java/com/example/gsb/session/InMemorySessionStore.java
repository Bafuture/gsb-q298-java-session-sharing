package com.example.gsb.session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 共享会话存储（用内存对象模拟集中式后端，如 Redis/数据库）。
 * 所有节点读写都经过它，因此：
 * <ul>
 *   <li>任意节点都能读到任意会话；</li>
 *   <li>版本替换基于 {@link ConcurrentHashMap#compute} 原子完成，
 *       读者只会看到某个完整版本；</li>
 *   <li>实例扩缩容不影响数据，会话不会因节点下线而丢失。</li>
 * </ul>
 */
public final class InMemorySessionStore {

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final Clock clock;
    private final AtomicLong versionConflicts = new AtomicLong();
    private final AtomicLong expiredRemovals = new AtomicLong();

    public InMemorySessionStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 创建会话，初始版本号为 1。同 id 且未过期的会话已存在时抛异常。
     */
    public Session create(String sessionId, Map<String, Object> attributes, Duration ttl) {
        Instant now = clock.instant();
        Session created = new Session(sessionId, attributes, 1, now, now, ttl);
        sessions.compute(sessionId, (id, current) -> {
            if (current != null && !current.isExpired(now)) {
                throw new IllegalStateException("session already exists: " + id);
            }
            return created;
        });
        return created;
    }

    /**
     * 读取会话。过期会话按“惰性过期”处理：视为不存在并顺手清理。
     */
    public SessionLookup find(String sessionId) {
        Session current = sessions.get(sessionId);
        if (current == null) {
            return new SessionLookup.NotFound(sessionId);
        }
        if (current.isExpired(clock.instant())) {
            if (sessions.remove(sessionId, current)) {
                expiredRemovals.incrementAndGet();
            }
            return new SessionLookup.NotFound(sessionId);
        }
        return new SessionLookup.Found(current);
    }

    /**
     * 乐观并发更新：仅当存储中当前版本等于 {@code expectedVersion} 时更新成功，
     * 否则按 {@code strategy} 处理冲突（见 {@link ConflictStrategy}）。
     *
     * @return 更新后（或冲突仲裁后）存储中的会话
     */
    public Session update(String sessionId, long expectedVersion,
                          Map<String, Object> attributes, ConflictStrategy strategy) {
        Objects.requireNonNull(strategy, "conflict strategy");
        Instant now = clock.instant();
        Session[] winner = new Session[1];
        sessions.compute(sessionId, (id, current) -> {
            if (current == null || current.isExpired(now)) {
                throw new NoSuchSessionException(id);
            }
            if (current.version() == expectedVersion) {
                Session next = current.nextVersion(attributes, now);
                winner[0] = next;
                return next;
            }
            versionConflicts.incrementAndGet();
            if (strategy == ConflictStrategy.REJECT_CONFLICT) {
                throw new VersionConflictException(id, expectedVersion, current.version());
            }
            // LAST_WRITE_WINS：按时间取新，时间相同保留已存储版本
            if (now.isAfter(current.lastModifiedAt())) {
                Session next = current.nextVersion(attributes, now);
                winner[0] = next;
                return next;
            }
            winner[0] = current;
            return current;
        });
        return winner[0];
    }

    /**
     * 使会话失效。返回是否真的移除了一条会话。
     */
    public boolean invalidate(String sessionId) {
        return sessions.remove(sessionId) != null;
    }

    /**
     * 主动清理所有已过期会话，返回清理条数。
     */
    public int sweepExpired() {
        Instant now = clock.instant();
        int removed = 0;
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            if (entry.getValue().isExpired(now)
                    && sessions.remove(entry.getKey(), entry.getValue())) {
                removed++;
            }
        }
        expiredRemovals.addAndGet(removed);
        return removed;
    }

    /** 当前未过期的会话数。 */
    public long activeSessionCount() {
        Instant now = clock.instant();
        return sessions.values().stream().filter(s -> !s.isExpired(now)).count();
    }

    public long versionConflictCount() {
        return versionConflicts.get();
    }

    public long expiredSessionCount() {
        return expiredRemovals.get();
    }
}
