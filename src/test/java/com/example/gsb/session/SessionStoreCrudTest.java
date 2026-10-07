package com.example.gsb.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 需求 1：会话创建、读取、更新与失效，会话带版本号。 */
class SessionStoreCrudTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(30);

    private MutableClock clock;
    private InMemorySessionStore store;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        store = new InMemorySessionStore(clock);
    }

    @Test
    void createAssignsVersionOneAndIsReadable() {
        Session created = store.create("s1", Map.of("user", "alice", "cart", 3), TTL);

        assertThat(created.version()).isEqualTo(1);
        assertThat(created.attributes()).containsEntry("user", "alice");

        SessionLookup lookup = store.find("s1");
        assertThat(lookup).isInstanceOf(SessionLookup.Found.class);
        assertThat(lookup.session()).isEqualTo(created);
    }

    @Test
    void readOfUnknownSessionReturnsExplicitNotFound() {
        SessionLookup lookup = store.find("missing");

        assertThat(lookup.found()).isFalse();
        assertThat(lookup).isEqualTo(new SessionLookup.NotFound("missing"));
    }

    @Test
    void duplicateCreateOfLiveSessionFails() {
        store.create("s1", Map.of(), TTL);

        assertThatThrownBy(() -> store.create("s1", Map.of(), TTL))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void updateWithExpectedVersionBumpsVersionAndReplacesAttributes() {
        store.create("s1", Map.of("step", "a"), TTL);
        clock.advance(Duration.ofSeconds(5));

        Session updated = store.update("s1", 1, Map.of("step", "b"),
                ConflictStrategy.REJECT_CONFLICT);

        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.attributes()).containsExactly(Map.entry("step", "b"));
        assertThat(updated.createdAt()).isEqualTo(T0);
        assertThat(updated.lastModifiedAt()).isEqualTo(T0.plusSeconds(5));
        assertThat(store.find("s1").session()).isEqualTo(updated);
    }

    @Test
    void updateWithStaleVersionIsRejected() {
        store.create("s1", Map.of(), TTL);
        store.update("s1", 1, Map.of(), ConflictStrategy.REJECT_CONFLICT);

        assertThatThrownBy(() -> store.update("s1", 1, Map.of(),
                ConflictStrategy.REJECT_CONFLICT))
                .isInstanceOf(VersionConflictException.class)
                .satisfies(e -> {
                    VersionConflictException vce = (VersionConflictException) e;
                    assertThat(vce.expectedVersion()).isEqualTo(1);
                    assertThat(vce.actualVersion()).isEqualTo(2);
                });
    }

    @Test
    void updateOfMissingSessionFails() {
        assertThatThrownBy(() -> store.update("nope", 1, Map.of(),
                ConflictStrategy.REJECT_CONFLICT))
                .isInstanceOf(NoSuchSessionException.class);
    }

    @Test
    void invalidateRemovesSessionAndSubsequentReadIsNotFound() {
        store.create("s1", Map.of(), TTL);

        assertThat(store.invalidate("s1")).isTrue();
        assertThat(store.invalidate("s1")).isFalse();
        assertThat(store.find("s1")).isEqualTo(new SessionLookup.NotFound("s1"));
    }

    @Test
    void sessionAttributesAreImmutableForCallers() {
        Session created = store.create("s1", Map.of("k", "v"), TTL);

        assertThatThrownBy(() -> created.attributes().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
