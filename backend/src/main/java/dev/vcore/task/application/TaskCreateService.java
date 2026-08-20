package dev.vcore.task.application;

import dev.vcore.identity.application.AuthenticatedUser;
import dev.vcore.shared.web.ApiConflictException;
import dev.vcore.shared.web.ResourceNotFoundException;
import dev.vcore.task.domain.WorkflowColumnEntity;
import dev.vcore.task.infrastructure.TaskRepository;
import dev.vcore.task.infrastructure.WorkflowColumnRepository;
import dev.vcore.workspace.application.WorkspaceAccessService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskCreateService {

    private final JdbcClient jdbcClient;
    private final TaskRepository taskRepository;
    private final WorkflowColumnRepository columnRepository;
    private final WorkspaceAccessService workspaceAccessService;
    private final IdempotencyService idempotencyService;
    private final TaskEventRecorder taskEventRecorder;

    public TaskCreateService(
            JdbcClient jdbcClient,
            TaskRepository taskRepository,
            WorkflowColumnRepository columnRepository,
            WorkspaceAccessService workspaceAccessService,
            IdempotencyService idempotencyService,
            TaskEventRecorder taskEventRecorder) {
        this.jdbcClient = jdbcClient;
        this.taskRepository = taskRepository;
        this.columnRepository = columnRepository;
        this.workspaceAccessService = workspaceAccessService;
        this.idempotencyService = idempotencyService;
        this.taskEventRecorder = taskEventRecorder;
    }

    @Transactional
    public CreateTaskResult create(
            AuthenticatedUser user,
            UUID workspaceId,
            UUID projectId,
            String idempotencyKey,
            CreateTaskCommand command) {
        workspaceAccessService.requireMutationAccess(user.id(), workspaceId);
        String title = command.title().strip();
        List<String> tags = normalizeTags(command.tags());
        String canonical = projectId + "|" + command.columnId() + "|" + title + "|"
                + command.priority().toLowerCase(Locale.ROOT) + "|" + command.points() + "|" + String.join(",", tags);
        String requestHash = RequestHash.sha256(canonical);
        var replay = idempotencyService.beginOrReplay(workspaceId, idempotencyKey, requestHash, CreateTaskResult.class);
        if (replay.isPresent()) {
            return replay.get();
        }

        String projectKey = jdbcClient
                .sql("""
                        SELECT project_key
                        FROM project
                        WHERE workspace_id = :workspaceId AND id = :projectId AND NOT archived
                        FOR UPDATE
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("The project does not exist."));
        WorkflowColumnEntity targetColumn = columnRepository
                .findForMove(command.columnId(), workspaceId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("The workflow column does not exist."));

        if (targetColumn.wipLimit() != null) {
            long currentWork =
                    taskRepository.countByWorkspaceIdAndProjectIdAndColumnId(workspaceId, projectId, targetColumn.id());
            if (currentWork >= targetColumn.wipLimit()) {
                throw new ApiConflictException(
                        "The target column WIP limit of " + targetColumn.wipLimit() + " has been reached.");
            }
        }

        long taskNumber = jdbcClient
                .sql("""
                        SELECT COALESCE(MAX(task_number), 0) + 1
                        FROM task
                        WHERE workspace_id = :workspaceId AND project_id = :projectId
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .query(Long.class)
                .single();
        long position = jdbcClient
                .sql("""
                        SELECT COALESCE(MAX(position), 0) + 1000
                        FROM task
                        WHERE workspace_id = :workspaceId AND project_id = :projectId AND column_id = :columnId
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("columnId", targetColumn.id())
                .query(Long.class)
                .single();
        UUID sprintId = jdbcClient
                .sql("""
                        SELECT id FROM sprint
                        WHERE workspace_id = :workspaceId AND project_id = :projectId AND status = 'ACTIVE'
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .query(UUID.class)
                .optional()
                .orElse(null);

        UUID taskId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcClient
                .sql("""
                        INSERT INTO task (
                            id, workspace_id, project_id, sprint_id, column_id, task_number,
                            title, priority, story_points, position, created_by, updated_by,
                            created_at, updated_at
                        ) VALUES (
                            :id, :workspaceId, :projectId, :sprintId, :columnId, :taskNumber,
                            :title, :priority, :points, :position, :actorId, :actorId,
                            :createdAt, :createdAt
                        )
                        """)
                .param("id", taskId)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("sprintId", sprintId)
                .param("columnId", targetColumn.id())
                .param("taskNumber", taskNumber)
                .param("title", title)
                .param("priority", command.priority().toUpperCase(Locale.ROOT))
                .param("points", command.points())
                .param("position", position)
                .param("actorId", user.id())
                .param("createdAt", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
        attachTags(workspaceId, taskId, tags);

        CreateTaskResult result = new CreateTaskResult(
                taskId,
                projectKey + "-" + taskNumber,
                title,
                user.displayName(),
                targetColumn.clientStatus(),
                command.priority().toLowerCase(Locale.ROOT),
                command.points(),
                targetColumn.id(),
                position,
                0,
                now,
                tags);
        taskEventRecorder.record(
                workspaceId,
                user.id(),
                taskId,
                "TASK_CREATED",
                "task.created.v1",
                Map.of("key", result.key(), "title", result.title(), "columnId", result.columnId()),
                now);
        idempotencyService.complete(workspaceId, idempotencyKey, 201, result);
        return result;
    }

    private List<String> normalizeTags(List<String> source) {
        return source.stream()
                .map(String::strip)
                .filter(tag -> !tag.isEmpty())
                .map(tag -> tag.toLowerCase(Locale.ROOT))
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private void attachTags(UUID workspaceId, UUID taskId, List<String> tags) {
        for (String tag : tags) {
            UUID candidateId = UUID.randomUUID();
            jdbcClient
                    .sql("""
                            INSERT INTO tag (id, workspace_id, name)
                            VALUES (:id, :workspaceId, :name)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("id", candidateId)
                    .param("workspaceId", workspaceId)
                    .param("name", tag)
                    .update();
            UUID tagId = jdbcClient
                    .sql("""
                            SELECT id FROM tag
                            WHERE workspace_id = :workspaceId AND LOWER(name) = :name
                            """)
                    .param("workspaceId", workspaceId)
                    .param("name", tag)
                    .query(UUID.class)
                    .single();
            jdbcClient
                    .sql("""
                            INSERT INTO task_tag (workspace_id, task_id, tag_id)
                            VALUES (:workspaceId, :taskId, :tagId)
                            """)
                    .param("workspaceId", workspaceId)
                    .param("taskId", taskId)
                    .param("tagId", tagId)
                    .update();
        }
    }

    public record CreateTaskCommand(String title, UUID columnId, String priority, int points, List<String> tags) {}

    public record CreateTaskResult(
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
