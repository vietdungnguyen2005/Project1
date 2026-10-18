package dev.vcore.activity.application;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class SseEventHub {
    private static final Logger LOGGER = LoggerFactory.getLogger(SseEventHub.class);
    private static final long EMITTER_TIMEOUT_MILLIS = 30 * 60 * 1000L;
    private final ConcurrentHashMap<UUID, Set<Subscriber>> subscribers = new ConcurrentHashMap<>();
    private final java.util.function.BiPredicate<UUID, SseSubscriptionAccess.Identity> authorization;

    @org.springframework.beans.factory.annotation.Autowired
    public SseEventHub(SseSubscriptionAccess access) {
        this(access::allowed);
    }

    SseEventHub(java.util.function.BiPredicate<UUID, SseSubscriptionAccess.Identity> authorization) {
        this.authorization = authorization;
    }

    public SseEmitter subscribe(UUID workspaceId, SseSubscriptionAccess.Identity identity) {
        if (!authorization.test(workspaceId, identity))
            throw new AccessDeniedException("Stream access is no longer valid.");
        var subscriber = new Subscriber(new SseEmitter(EMITTER_TIMEOUT_MILLIS), identity);
        subscribers.compute(workspaceId, (id, existing) -> {
            var set = existing == null ? ConcurrentHashMap.<Subscriber>newKeySet() : existing;
            set.add(subscriber);
            return set;
        });
        Runnable remove = () -> unregister(workspaceId, subscriber);
        subscriber.emitter.onCompletion(remove);
        subscriber.emitter.onTimeout(remove);
        subscriber.emitter.onError(error -> remove.run());
        send(workspaceId, subscriber, SseEmitter.event().comment("connected"));
        return subscriber.emitter;
    }

    public void publish(OutboxEvent event) {
        Set<Subscriber> active = subscribers.get(event.workspaceId());
        if (active == null) return;
        active.forEach(subscriber -> send(
                event.workspaceId(),
                subscriber,
                SseEmitter.event()
                        .id(event.id().toString())
                        .name(event.eventType())
                        .data(event.payload())));
    }

    @Scheduled(fixedDelayString = "${vcore.sse.heartbeat-interval:15000}")
    void heartbeat() {
        subscribers.forEach((workspaceId, active) -> active.forEach(
                subscriber -> send(workspaceId, subscriber, SseEmitter.event().comment("heartbeat"))));
    }

    private void send(UUID workspaceId, Subscriber subscriber, SseEmitter.SseEventBuilder event) {
        synchronized (subscriber) {
            if (subscriber.closed) return;
            try {
                // Recheck before every business event as well as idle heartbeats.
                if (!authorization.test(workspaceId, subscriber.identity)) {
                    close(workspaceId, subscriber);
                    return;
                }
                subscriber.emitter.send(event);
            } catch (DataAccessException exception) {
                LOGGER.debug("Closing SSE subscription because authorization cannot be verified", exception);
                close(workspaceId, subscriber);
            } catch (IOException | IllegalStateException exception) {
                close(workspaceId, subscriber);
            }
        }
    }

    private void close(UUID workspaceId, Subscriber subscriber) {
        if (!unregister(workspaceId, subscriber)) return;
        try {
            subscriber.emitter.complete();
        } catch (IllegalStateException ignored) {
            // A disconnected servlet may already have completed its async error lifecycle.
        }
    }

    private boolean unregister(UUID workspaceId, Subscriber subscriber) {
        synchronized (subscriber) {
            if (subscriber.closed) return false;
            subscriber.closed = true;
            subscribers.computeIfPresent(workspaceId, (id, active) -> {
                active.remove(subscriber);
                return active.isEmpty() ? null : active;
            });
            return true;
        }
    }

    private static final class Subscriber {
        final SseEmitter emitter;
        final SseSubscriptionAccess.Identity identity;
        boolean closed;

        Subscriber(SseEmitter emitter, SseSubscriptionAccess.Identity identity) {
            this.emitter = emitter;
            this.identity = identity;
        }
    }

    public record OutboxEvent(UUID id, UUID workspaceId, String eventType, String payload) {}
}
