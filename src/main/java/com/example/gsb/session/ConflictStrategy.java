package com.example.gsb.session;

/**
 * 并发更新冲突处理策略（按版本号检测冲突后如何处置）：
 * REJECT_STALE   —— 乐观锁：拒绝版本落后的更新，抛出 VersionConflictException；
 * LAST_WRITE_WINS —— 按时间取新：时间戳更新的写覆盖旧值，时间戳更旧的写被丢弃。
 */
public enum ConflictStrategy {
    REJECT_STALE,
    LAST_WRITE_WINS
}
