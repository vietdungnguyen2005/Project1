package dev.vcore.activity.application;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class SseEventHub {

    private static final long EMITTER_TIMEOUT_MILLIS = 30 * 60 * 1000L;

    private final ConcurrentHashMap<UUID, Set<SseEmitter>> emittersByWorkspace = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID workspaceId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        Set<SseEmitter> workspaceEmitters =
                emittersByWorkspace.computeIfAbsent(workspaceId, ignored -> ConcurrentHashMap.newKeySet());
        workspaceEmitters.add(emitter);
        Runnable remove = () -> remove(workspaceId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        return emitter;
    }

    public void publish(OutboxEvent event) {
        Set<SseEmitter> emitters = emittersByWorkspace.get(event.workspaceId());
        if (emitters == null) {
            return;
        }
        emitters.forEach(emitter -> send(
                event.workspaceId(),
                emitter,
                SseEmitter.event()
                        .id(event.id().toString())
                        .name(event.eventType())
                        .data(event.payload())));
    }

    @Scheduled(fixedDelayString = "${vcore.sse.heartbeat-interval:15000}")
    void heartbeat() {
        emittersByWorkspace.forEach((workspaceId, emitters) -> emitters.forEach(
                emitter -> send(workspaceId, emitter, SseEmitter.event().comment("heartbeat"))));
    }

    private void send(UUID workspaceId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException exception) {
            remove(workspaceId, emitter);
            emitter.complete();
        }
    }

    private void remove(UUID workspaceId, SseEmitter emitter) {
        Set<SseEmitter> emitters = emittersByWorkspace.get(workspaceId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByWorkspace.remove(workspaceId, emitters);
        }
    }

    public record OutboxEvent(UUID id, UUID workspaceId, String eventType, String payload) {}
}
