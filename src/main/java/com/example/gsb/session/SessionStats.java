package com.example.gsb.session;

/**
 * 集群运行统计快照。
 *
 * @param activeSessions   当前未过期会话数
 * @param versionConflicts 累计版本冲突次数
 * @param scaleOuts        累计扩容次数
 * @param scaleIns         累计缩容次数
 * @param expiredSessions  累计过期清理会话数（主动清理 + 惰性过期）
 */
public record SessionStats(
        long activeSessions,
        long versionConflicts,
        long scaleOuts,
        long scaleIns,
        long expiredSessions) {
}
