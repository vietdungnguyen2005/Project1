package dev.vcore.activity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class SseEventHubTest {
    @Test
    void closesAndUnregistersAStreamWhenAuthorizationStorageCannotBeRead() throws Exception {
        var workspace = UUID.randomUUID();
        var identity = new SseSubscriptionAccess.Identity(UUID.randomUUID(), "test-session", false);
        var outage = new java.util.concurrent.atomic.AtomicBoolean();
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        var hub = new SseEventHub((id, principal) -> {
            checks.incrementAndGet();
            assertThat(id).isEqualTo(workspace);
            assertThat(principal).isEqualTo(identity);
            if (outage.get()) throw new DataAccessResourceFailureException("Test DB outage");
            return true;
        });
        var emitter = hub.subscribe(workspace, identity);
        outage.set(true);
        var event = new SseEventHub.OutboxEvent(UUID.randomUUID(), workspace, "task.created.v1", "must-not-leak");
        assertThatCode(() -> hub.publish(event)).doesNotThrowAnyException();
        assertThatThrownBy(() -> emitter.send("not delivered")).isInstanceOf(IllegalStateException.class);
        outage.set(false);
        hub.publish(event);
        hub.heartbeat();
        // One initial check, one connected comment, one failed revalidation; removed streams stay closed.
        assertThat(checks.get()).isEqualTo(3);
    }
}
