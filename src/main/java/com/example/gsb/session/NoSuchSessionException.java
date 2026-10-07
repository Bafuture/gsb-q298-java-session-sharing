package com.example.gsb.session;

/** 会话不存在（从未创建、已失效或已过期）时抛出。 */
public class NoSuchSessionException extends RuntimeException {

    public NoSuchSessionException(String sessionId) {
        super("session not found: " + sessionId);
    }
}
