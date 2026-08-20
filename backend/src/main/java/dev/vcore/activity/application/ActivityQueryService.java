package dev.vcore.activity.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vcore.identity.application.AuthenticatedUser;
import dev.vcore.workspace.application.WorkspaceAccessService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivityQueryService {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final WorkspaceAccessService workspaceAccessService;

    public ActivityQueryService(
            JdbcClient jdbcClient, ObjectMapper objectMapper, WorkspaceAccessService workspaceAccessService) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.workspaceAccessService = workspaceAccessService;
    }

    @Transactional(readOnly = true)
    public ActivityListView list(AuthenticatedUser user, UUID workspaceId, int requestedLimit) {
        workspaceAccessService.requireMembership(user.id(), workspaceId);
        int limit = Math.clamp(requestedLimit, 1, 100);
        List<ActivityView> items = jdbcClient
                .sql("""
                        SELECT activity.id,
                               actor.display_name AS actor,
                               activity.action,
                               activity.aggregate_type,
                               activity.aggregate_id,
                               CASE
                                   WHEN activity.aggregate_type = 'TASK'
                                       THEN task_project.project_key || '-' || task.task_number
                                   WHEN activity.aggregate_type = 'PROJECT'
                                       THEN aggregate_project.project_key
                                   WHEN activity.aggregate_type = 'WORKSPACE'
                                       THEN aggregate_workspace.slug
                                   ELSE NULL
                               END AS aggregate_key,
                               CASE
                                   WHEN activity.aggregate_type = 'TASK' THEN task.title
                                   WHEN activity.aggregate_type = 'PROJECT' THEN aggregate_project.name
                                   WHEN activity.aggregate_type = 'WORKSPACE' THEN aggregate_workspace.name
                                   ELSE NULL
                               END AS aggregate_title,
                               activity.details::text AS details,
                               activity.occurred_at
                        FROM activity
                        JOIN app_user actor ON actor.id = activity.actor_id
                        LEFT JOIN task
                          ON activity.aggregate_type = 'TASK'
                         AND task.workspace_id = activity.workspace_id
                         AND task.id = activity.aggregate_id
                        LEFT JOIN project task_project
                          ON task_project.workspace_id = task.workspace_id
                         AND task_project.id = task.project_id
                        LEFT JOIN project aggregate_project
                          ON activity.aggregate_type = 'PROJECT'
                         AND aggregate_project.workspace_id = activity.workspace_id
                         AND aggregate_project.id = activity.aggregate_id
                        LEFT JOIN workspace aggregate_workspace
                          ON activity.aggregate_type = 'WORKSPACE'
                         AND aggregate_workspace.id = activity.aggregate_id
                        WHERE activity.workspace_id = :workspaceId
                        ORDER BY activity.occurred_at DESC, activity.id DESC
                        LIMIT :limit
                        """)
                .param("workspaceId", workspaceId)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new ActivityView(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("actor"),
                        resultSet.getString("action"),
                        resultSet.getString("aggregate_type"),
                        resultSet.getObject("aggregate_id", UUID.class),
                        resultSet.getString("aggregate_key"),
                        resultSet.getString("aggregate_title"),
                        parseDetails(resultSet.getString("details")),
                        resultSet
                                .getObject("occurred_at", java.time.OffsetDateTime.class)
                                .toInstant()))
                .list();
        return new ActivityListView(items);
    }

    private JsonNode parseDetails(String details) {
        try {
            return objectMapper.readTree(details);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored activity details are unreadable.", exception);
        }
    }

    public record ActivityListView(List<ActivityView> items) {}

    public record ActivityView(
            UUID id,
            String actor,
            String action,
            String aggregateType,
            UUID aggregateId,
            String aggregateKey,
            String aggregateTitle,
            JsonNode details,
            Instant occurredAt) {}
}
