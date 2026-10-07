package com.example.gsb.session;

/**
 * 乐观并发冲突策略：更新时携带期望版本号，与存储中当前版本不一致即为冲突。
 */
public enum ConflictStrategy {

    /** 拒绝后来的更新：直接抛出 {@link VersionConflictException}，存储内容不变。 */
    REJECT_CONFLICT,

    /**
     * 按时间取新：比较本次写入时间与已存储版本的最后修改时间，
     * 时间更新的一方获胜；时间相同时保留已存储版本（本次写入丢弃）。
     */
    LAST_WRITE_WINS
}
