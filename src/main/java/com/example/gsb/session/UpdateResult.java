package com.example.gsb.session;

/**
 * 一次更新的结果。
 *
 * @param session          更新后存储中的当前会话（无论本次写入是否生效）
 * @param conflictDetected 是否检测到版本冲突（期望版本与存储版本不一致）
 * @param applied          本次写入是否真正生效（LAST_WRITE_WINS 下旧时间戳的写会被丢弃）
 */
public record UpdateResult(Session session, boolean conflictDetected, boolean applied) {
}
