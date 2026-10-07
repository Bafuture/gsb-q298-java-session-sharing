package com.example.gsb.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一个不可变的会话快照。每次更新都会生成版本号 +1 的新对象，
 * 引用替换是原子的，因此任何读者看到的都必然是某个完整版本，
 * 不会读到属性更新到一半的中间状态。
 */
public record Session(
        String id,
        Map<String, Object> attributes,
        long version,
        Instant createdAt,
        Instant lastModifiedAt,
        Duration ttl) {

    public Session {
        Objects.requireNonNull(id, "session id");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(lastModifiedAt, "lastModifiedAt");
        Objects.requireNonNull(ttl, "ttl");
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1, got " + version);
        }
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /**
     * 基于当前版本生成下一版本；createdAt 保持不变。
     */
    public Session nextVersion(Map<String, Object> newAttributes, Instant modifiedAt) {
        return new Session(id, newAttributes, version + 1, createdAt, modifiedAt, ttl);
    }

    public Instant expiresAt() {
        return lastModifiedAt.plus(ttl);
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt());
    }
}
