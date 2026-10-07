package com.example.gsb.session;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 会话的不可变快照。每次更新都会产生一个全新的 Session 实例（版本号 +1），
 * 因此任何读到的 Session 都是某个完整版本，不会读到更新到一半的状态。
 */
public final class Session {

    /** 表示永不过期的 expiresAt 取值。 */
    public static final long NEVER_EXPIRES = Long.MAX_VALUE;

    private final String id;
    private final long version;
    private final Map<String, String> attributes;
    private final long createdAtMillis;
    private final long lastModifiedAtMillis;
    private final long ttlMillis;
    private final long expiresAtMillis;
    private final String ownerNodeId;

    public Session(String id,
                   long version,
                   Map<String, String> attributes,
                   long createdAtMillis,
                   long lastModifiedAtMillis,
                   long ttlMillis,
                   String ownerNodeId) {
        this.id = Objects.requireNonNull(id, "id");
        this.version = version;
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        this.createdAtMillis = createdAtMillis;
        this.lastModifiedAtMillis = lastModifiedAtMillis;
        this.ttlMillis = ttlMillis;
        this.expiresAtMillis = ttlMillis == Long.MAX_VALUE ? NEVER_EXPIRES : lastModifiedAtMillis + ttlMillis;
        this.ownerNodeId = ownerNodeId;
    }

    /** 基于当前版本生成下一个版本（版本号 +1，属性整体替换，过期时间顺延）。 */
    public Session withAttributes(Map<String, String> newAttributes, long nowMillis) {
        return new Session(id, version + 1, newAttributes, createdAtMillis, nowMillis, ttlMillis, ownerNodeId);
    }

    public boolean isExpired(long nowMillis) {
        return nowMillis >= expiresAtMillis;
    }

    public String getId() {
        return id;
    }

    public long getVersion() {
        return version;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public long getLastModifiedAtMillis() {
        return lastModifiedAtMillis;
    }

    public long getTtlMillis() {
        return ttlMillis;
    }

    public long getExpiresAtMillis() {
        return expiresAtMillis;
    }

    /** 粘性模式下创建该会话的节点 id；非粘性模式为 null。 */
    public String getOwnerNodeId() {
        return ownerNodeId;
    }

    @Override
    public String toString() {
        return "Session{id=" + id + ", version=" + version + ", attributes=" + attributes + "}";
    }
}
