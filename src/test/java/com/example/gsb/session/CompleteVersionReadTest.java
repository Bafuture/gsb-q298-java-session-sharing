package com.example.gsb.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 需求 2：多节点共享，任意节点都能读到会话，且读到的内容必然是某个完整版本，
 * 不会出现属性更新到一半的混合状态。
 */
class CompleteVersionReadTest {

    @Test
    void anyNodeReadsTheSession() {
        SessionCluster cluster = new SessionCluster(Clock.systemUTC());
        SessionNode nodeA = cluster.scaleOut(NodeMode.NON_STICKY, ConflictStrategy.REJECT_CONFLICT);
        SessionNode nodeB = cluster.scaleOut(NodeMode.NON_STICKY, ConflictStrategy.REJECT_CONFLICT);
        SessionNode nodeC = cluster.scaleOut(NodeMode.NON_STICKY, ConflictStrategy.REJECT_CONFLICT);

        nodeA.createSession("s1", Map.of("user", "alice"), Duration.ofMinutes(10));

        for (SessionNode node : List.of(nodeA, nodeB, nodeC)) {
            assertThat(node.read("s1")).isInstanceOf(SessionLookup.Found.class);
            assertThat(node.read("s1").session().attributes())
                    .containsEntry("user", "alice");
        }
    }

    @Test
    void concurrentReadersAlwaysObserveACompleteVersion() throws Exception {
        InMemorySessionStore store = new InMemorySessionStore(Clock.systemUTC());
        store.create("s1", Map.of("step", 1L, "payload", 10L), Duration.ofMinutes(10));

        int updates = 300;
        int readers = 4;
        AtomicReference<AssertionError> failure = new AtomicReference<>();

        Thread writer = new Thread(() -> {
            long version = 1;
            for (long step = 2; step <= updates + 1L; step++) {
                try {
                    Session next = store.update("s1", version,
                            Map.of("step", step, "payload", step * 10),
                            ConflictStrategy.REJECT_CONFLICT);
                    version = next.version();
                } catch (AssertionError e) {
                    failure.compareAndSet(null, e);
                }
            }
        }, "writer");

        List<Thread> readerThreads = new java.util.ArrayList<>();
        for (int r = 0; r < readers; r++) {
            Thread reader = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        SessionLookup lookup = store.find("s1");
                        if (lookup instanceof SessionLookup.Found f) {
                            Session s = f.session();
                            long step = (long) s.attributes().get("step");
                            long payload = (long) s.attributes().get("payload");
                            // 不变量：版本、step、payload 永远一致，即读者只看到完整版本
                            if (s.version() != step || payload != step * 10) {
                                failure.compareAndSet(null, new AssertionError(
                                        "partial version observed: v=" + s.version()
                                                + " step=" + step + " payload=" + payload));
                                return;
                            }
                        }
                    } catch (AssertionError e) {
                        failure.compareAndSet(null, e);
                        return;
                    }
                }
            }, "reader-" + r);
            readerThreads.add(reader);
        }

        readerThreads.forEach(Thread::start);
        writer.start();
        writer.join();
        readerThreads.forEach(Thread::interrupt);
        for (Thread t : readerThreads) {
            t.join();
        }

        assertThat(failure.get()).isNull();
        Session finalSession = store.find("s1").session();
        assertThat(finalSession.version()).isEqualTo(updates + 1L);
        assertThat(finalSession.attributes()).containsEntry("step", updates + 1L);
    }
}
