package com.example.gsb.session;

/** 对不存在（或已过期/已失效）的会话执行写操作时抛出。 */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(String sessionId) {
        super("会话不存在：" + sessionId);
    }
}
