package dev.vcore.task.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vcore.shared.web.ApiConflictException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class IdempotencyService {

    private static final Duration RETENTION = Duration.ofHours(24);

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public <T> Optional<T> beginOrReplay(UUID workspaceId, String key, String requestHash, Class<T> responseType) {
        Instant now = Instant.now();
        int inserted = jdbcClient
                .sql("""
                        INSERT INTO idempotency_record (
                            workspace_id, idempotency_key, request_hash, created_at, expires_at
                        )
                        VALUES (:workspaceId, :key, :requestHash, :createdAt, :expiresAt)
                        ON CONFLICT (workspace_id, idempotency_key) DO NOTHING
                        """)
                .param("workspaceId", workspaceId)
                .param("key", key)
                .param("requestHash", requestHash)
                .param("createdAt", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("expiresAt", OffsetDateTime.ofInstant(now.plus(RETENTION), ZoneOffset.UTC))
                .update();

        if (inserted == 1) {
            return Optional.empty();
        }

        StoredResponse stored = jdbcClient
                .sql("""
                        SELECT request_hash, response_status, response_body::text AS response_body
                        FROM idempotency_record
                        WHERE workspace_id = :workspaceId AND idempotency_key = :key
                        """)
                .param("workspaceId", workspaceId)
                .param("key", key)
                .query((resultSet, rowNumber) -> new StoredResponse(
                        resultSet.getString("request_hash"),
                        resultSet.getObject("response_status", Integer.class),
                        resultSet.getString("response_body")))
                .single();

        if (!stored.requestHash().equals(requestHash)) {
            throw new ApiConflictException("The idempotency key was already used for a different request.");
        }
        if (stored.status() == null || stored.body() == null) {
            throw new ApiConflictException("An identical request is already in progress.");
        }

        try {
            return Optional.of(objectMapper.readValue(stored.body(), responseType));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored idempotency response is unreadable.", exception);
        }
    }

    public void complete(UUID workspaceId, String key, int responseStatus, Object result) {
        try {
            String responseBody = objectMapper.writeValueAsString(result);
            int updated = jdbcClient
                    .sql("""
                            UPDATE idempotency_record
                            SET response_status = :responseStatus, response_body = CAST(:responseBody AS jsonb)
                            WHERE workspace_id = :workspaceId AND idempotency_key = :key
                            """)
                    .param("responseBody", responseBody)
                    .param("responseStatus", responseStatus)
                    .param("workspaceId", workspaceId)
                    .param("key", key)
                    .update();
            if (updated != 1) {
                throw new IllegalStateException("Idempotency completion did not update exactly one record.");
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize idempotency response.", exception);
        }
    }

    private record StoredResponse(String requestHash, Integer status, String body) {}
}
