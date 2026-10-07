package com.example.gsb.session;

/** 创建已存在的会话时抛出。 */
public class SessionAlreadyExistsException extends RuntimeException {

    public SessionAlreadyExistsException(String sessionId) {
        super("会话已存在：" + sessionId);
    }
}
