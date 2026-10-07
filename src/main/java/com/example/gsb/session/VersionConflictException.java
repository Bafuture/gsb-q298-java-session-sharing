package com.example.gsb.session;

/**
 * 乐观版本冲突：期望版本与存储中当前版本不一致，且冲突策略为拒绝。
 */
public class VersionConflictException extends RuntimeException {

    private final String sessionId;
    private final long expectedVersion;
    private final long actualVersion;

    public VersionConflictException(String sessionId, long expectedVersion, long actualVersion) {
        super("version conflict on session " + sessionId
                + ": expected " + expectedVersion + ", actual " + actualVersion);
        this.sessionId = sessionId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public String sessionId() {
        return sessionId;
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    public long actualVersion() {
        return actualVersion;
    }
}
