package com.example.gsb.session;

/** 版本冲突且策略为 REJECT_STALE 时抛出。 */
public class VersionConflictException extends RuntimeException {

    private final String sessionId;
    private final long expectedVersion;
    private final long actualVersion;

    public VersionConflictException(String sessionId, long expectedVersion, long actualVersion) {
        super("会话 " + sessionId + " 版本冲突：期望 v" + expectedVersion + "，实际 v" + actualVersion);
        this.sessionId = sessionId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public String getSessionId() {
        return sessionId;
    }

    public long getExpectedVersion() {
        return expectedVersion;
    }

    public long getActualVersion() {
        return actualVersion;
    }
}
