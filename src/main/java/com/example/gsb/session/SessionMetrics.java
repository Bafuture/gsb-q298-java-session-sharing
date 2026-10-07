package com.example.gsb.session;

import java.util.concurrent.atomic.AtomicLong;

/** 组件运行统计：版本冲突次数、扩容次数、缩容次数、过期清理数。会话数由集群实时计算。 */
public final class SessionMetrics {

    private final AtomicLong versionConflicts = new AtomicLong();
    private final AtomicLong scaleOuts = new AtomicLong();
    private final AtomicLong scaleIns = new AtomicLong();
    private final AtomicLong expiredRemovals = new AtomicLong();

    void recordVersionConflict() {
        versionConflicts.incrementAndGet();
    }

    void recordScaleOut() {
        scaleOuts.incrementAndGet();
    }

    void recordScaleIn() {
        scaleIns.incrementAndGet();
    }

    void recordExpiredRemoval() {
        expiredRemovals.incrementAndGet();
    }

    public long getVersionConflicts() {
        return versionConflicts.get();
    }

    public long getScaleOuts() {
        return scaleOuts.get();
    }

    public long getScaleIns() {
        return scaleIns.get();
    }

    public long getExpiredRemovals() {
        return expiredRemovals.get();
    }
}
