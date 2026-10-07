package com.example.gsb.session;

/**
 * 会话读取结果：要么找到某个完整版本，要么明确不存在
 * （包括从未创建、已失效、已过期清理三种情况）。
 */
public sealed interface SessionLookup
        permits SessionLookup.Found, SessionLookup.NotFound {

    record Found(Session session) implements SessionLookup {
    }

    record NotFound(String sessionId) implements SessionLookup {
    }

    default boolean found() {
        return this instanceof Found;
    }

    default Session session() {
        if (this instanceof Found f) {
            return f.session();
        }
        throw new NoSuchSessionException(
                this instanceof NotFound n ? n.sessionId() : "<unknown>");
    }
}
