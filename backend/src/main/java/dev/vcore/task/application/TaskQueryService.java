package dev.vcore.task.application;

import dev.vcore.identity.application.AuthenticatedUser;
import dev.vcore.workspace.application.WorkspaceAccessService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskQueryService {

    private static final int BOARD_TASK_LIMIT = 200;

    private final JdbcClient jdbcClient;
    private final WorkspaceAccessService workspaceAccessService;

    public TaskQueryService(JdbcClient jdbcClient, WorkspaceAccessService workspaceAccessService) {
        this.jdbcClient = jdbcClient;
        this.workspaceAccessService = workspaceAccessService;
    }

    @Transactional(readOnly = true)
    public TaskListView listBoardTasks(AuthenticatedUser user, UUID workspaceId, UUID projectId) {
        workspaceAccessService.requireMembership(user.id(), workspaceId);

        List<TaskView> tasks = jdbcClient
                .sql("""
                        SELECT t.id,
                               p.project_key || '-' || t.task_number AS task_key,
                               t.title,
                               COALESCE(assignee.display_name, owner.display_name) AS owner,
                               CASE
                                   WHEN c.category = 'BACKLOG' THEN 'backlog'
                                   WHEN c.category = 'DONE' THEN 'done'
                                   WHEN LOWER(c.name) = 'review' THEN 'review'
                                   ELSE 'in-progress'
                               END AS status,
                               LOWER(t.priority) AS priority,
                               COALESCE(t.story_points, 0) AS points,
                               t.column_id,
                               t.position,
                               t.version,
                               t.updated_at,
                               ARRAY(
                                   SELECT tag.name
                                   FROM task_tag task_tag
                                   JOIN tag ON tag.workspace_id = task_tag.workspace_id AND tag.id = task_tag.tag_id
                                   WHERE task_tag.workspace_id = t.workspace_id AND task_tag.task_id = t.id
                                   ORDER BY tag.name
                               ) AS tags
                        FROM task t
                        JOIN project p
                          ON p.workspace_id = t.workspace_id AND p.id = t.project_id
                        JOIN workflow_column c
                          ON c.workspace_id = t.workspace_id AND c.id = t.column_id
                        JOIN app_user owner ON owner.id = t.updated_by
                        LEFT JOIN app_user assignee ON assignee.id = t.assignee_id
                        WHERE t.workspace_id = :workspaceId AND t.project_id = :projectId
                        ORDER BY c.position, t.position, t.id
                        LIMIT :limit
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("limit", BOARD_TASK_LIMIT + 1)
                .query((resultSet, rowNumber) -> new TaskView(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("task_key"),
                        resultSet.getString("title"),
                        resultSet.getString("owner"),
                        resultSet.getString("status"),
                        resultSet.getString("priority"),
                        resultSet.getInt("points"),
                        resultSet.getObject("column_id", UUID.class),
                        resultSet.getLong("position"),
                        resultSet.getLong("version"),
                        resultSet
                                .getObject("updated_at", java.time.OffsetDateTime.class)
                                .toInstant(),
                        readTags(resultSet)))
                .list();

        boolean truncated = tasks.size() > BOARD_TASK_LIMIT;
        List<TaskView> items = truncated ? List.copyOf(tasks.subList(0, BOARD_TASK_LIMIT)) : tasks;
        return new TaskListView(items, truncated);
    }

    private static List<String> readTags(ResultSet resultSet) throws SQLException {
        String[] tags = (String[]) resultSet.getArray("tags").getArray();
        return List.copyOf(Arrays.asList(tags));
    }

    public record TaskListView(List<TaskView> items, boolean truncated) {}

    public record TaskView(
            UUID id,
            String key,
            String title,
            String owner,
            String status,
            String priority,
            int points,
            UUID columnId,
            long position,
            long version,
            Instant updatedAt,
            List<String> tags) {}
}
