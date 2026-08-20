package dev.vcore.activity.application;

import dev.vcore.activity.application.SseEventHub.OutboxEvent;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OutboxClaimService {

    private static final int BATCH_SIZE = 50;

    private final JdbcClient jdbcClient;

    OutboxClaimService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    List<OutboxEvent> claimBatch() {
        List<OutboxEvent> events = jdbcClient
                .sql("""
                        SELECT id, workspace_id, event_type, payload::text AS payload
                        FROM outbox_event
                        WHERE published_at IS NULL AND next_attempt_at <= CURRENT_TIMESTAMP
                        ORDER BY occurred_at, id
                        FOR UPDATE SKIP LOCKED
                        LIMIT :limit
                        """)
                .param("limit", BATCH_SIZE)
                .query((resultSet, rowNumber) -> new OutboxEvent(
                        resultSet.getObject("id", java.util.UUID.class),
                        resultSet.getObject("workspace_id", java.util.UUID.class),
                        resultSet.getString("event_type"),
                        resultSet.getString("payload")))
                .list();

        OffsetDateTime publishedAt = OffsetDateTime.now(ZoneOffset.UTC);
        events.forEach(event -> jdbcClient
                .sql("""
                        UPDATE outbox_event
                        SET published_at = :publishedAt, attempt_count = attempt_count + 1
                        WHERE id = :id AND published_at IS NULL
                        """)
                .param("publishedAt", publishedAt)
                .param("id", event.id())
                .update());
        return events;
    }
}
