package dev.vcore.workspace.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class WorkspaceEventRecorder {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    WorkspaceEventRecorder(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    void record(
            UUID workspaceId,
            UUID actorId,
            String aggregateType,
            UUID aggregateId,
            String action,
            String eventType,
            Map<String, Object> details,
            Instant occurredAt) {
        try {
            String payload = objectMapper.writeValueAsString(details);
            OffsetDateTime databaseTime = OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC);
            jdbcClient
                    .sql("""
                            INSERT INTO activity (
                                id, workspace_id, actor_id, aggregate_type, aggregate_id,
                                action, details, occurred_at
                            ) VALUES (
                                :id, :workspaceId, :actorId, :aggregateType, :aggregateId,
                                :action, CAST(:details AS jsonb), :occurredAt
                            )
                            """)
                    .param("id", UUID.randomUUID())
                    .param("workspaceId", workspaceId)
                    .param("actorId", actorId)
                    .param("aggregateType", aggregateType)
                    .param("aggregateId", aggregateId)
                    .param("action", action)
                    .param("details", payload)
                    .param("occurredAt", databaseTime)
                    .update();
            jdbcClient
                    .sql("""
                            INSERT INTO outbox_event (
                                id, workspace_id, aggregate_type, aggregate_id,
                                event_type, payload, occurred_at
                            ) VALUES (
                                :id, :workspaceId, :aggregateType, :aggregateId,
                                :eventType, CAST(:payload AS jsonb), :occurredAt
                            )
                            """)
                    .param("id", UUID.randomUUID())
                    .param("workspaceId", workspaceId)
                    .param("aggregateType", aggregateType)
                    .param("aggregateId", aggregateId)
                    .param("eventType", eventType)
                    .param("payload", payload)
                    .param("occurredAt", databaseTime)
                    .update();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize the workspace event.", exception);
        }
    }
}
