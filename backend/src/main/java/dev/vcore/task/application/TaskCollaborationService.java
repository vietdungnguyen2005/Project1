package dev.vcore.task.application;

import dev.vcore.identity.application.AuthenticatedUser;
import dev.vcore.shared.web.ApiConflictException;
import dev.vcore.shared.web.ResourceNotFoundException;
import dev.vcore.workspace.application.WorkspaceAccessService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskCollaborationService {

    private final JdbcClient jdbcClient;
    private final WorkspaceAccessService workspaceAccessService;
    private final IdempotencyService idempotencyService;
    private final TaskEventRecorder taskEventRecorder;

    public TaskCollaborationService(
            JdbcClient jdbcClient,
            WorkspaceAccessService workspaceAccessService,
            IdempotencyService idempotencyService,
            TaskEventRecorder taskEventRecorder) {
        this.jdbcClient = jdbcClient;
        this.workspaceAccessService = workspaceAccessService;
        this.idempotencyService = idempotencyService;
        this.taskEventRecorder = taskEventRecorder;
    }

    @Transactional
    public AssignmentView assign(
            AuthenticatedUser user,
            UUID workspaceId,
            UUID projectId,
            UUID taskId,
            String idempotencyKey,
            UUID assigneeId,
            long expectedVersion) {
        workspaceAccessService.requireMutationAccess(user.id(), workspaceId);
        String requestHash = RequestHash.sha256(projectId + "|" + taskId + "|" + assigneeId + "|" + expectedVersion);
        var replay = idempotencyService.beginOrReplay(workspaceId, idempotencyKey, requestHash, AssignmentView.class);
        if (replay.isPresent()) {
            return replay.get();
        }

        long currentVersion = jdbcClient
                .sql("""
                        SELECT version
                        FROM task
                        WHERE id = :taskId AND workspace_id = :workspaceId AND project_id = :projectId
                        FOR UPDATE
                        """)
                .param("taskId", taskId)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("The task does not exist."));
        if (currentVersion != expectedVersion) {
            throw new ApiConflictException(
                    "The task changed after it was loaded. Current version is " + currentVersion + ".");
        }

        AssigneeView assignee = jdbcClient
                .sql("""
                        SELECT app_user.id, app_user.display_name, app_user.email
                        FROM workspace_membership membership
                        JOIN app_user ON app_user.id = membership.user_id
                        WHERE membership.workspace_id = :workspaceId AND membership.user_id = :assigneeId
                        """)
                .param("workspaceId", workspaceId)
                .param("assigneeId", assigneeId)
                .query((row, number) -> new AssigneeView(
                        row.getObject("id", UUID.class), row.getString("display_name"), row.getString("email")))
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("The assignee is not a workspace member."));

        Instant changedAt = Instant.now();
        jdbcClient
                .sql("""
                        UPDATE task
                        SET assignee_id = :assigneeId,
                            updated_by = :actorId,
                            version = version + 1,
                            updated_at = :updatedAt
                        WHERE id = :taskId AND workspace_id = :workspaceId AND project_id = :projectId
                        """)
                .param("assigneeId", assigneeId)
                .param("actorId", user.id())
                .param("updatedAt", OffsetDateTime.ofInstant(changedAt, ZoneOffset.UTC))
                .param("taskId", taskId)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .update();

        AssignmentView result = new AssignmentView(taskId, assignee, currentVersion + 1, changedAt);
        taskEventRecorder.record(
                workspaceId,
                user.id(),
                taskId,
                "TASK_ASSIGNED",
                "task.assigned.v1",
                Map.of("assigneeId", assigneeId, "assignee", assignee.name(), "version", currentVersion + 1),
                changedAt);
        idempotencyService.complete(workspaceId, idempotencyKey, 200, result);
        return result;
    }

    @Transactional
    public CommentView comment(
            AuthenticatedUser user,
            UUID workspaceId,
            UUID projectId,
            UUID taskId,
            String idempotencyKey,
            String rawBody) {
        workspaceAccessService.requireMutationAccess(user.id(), workspaceId);
        String body = rawBody.strip();
        String requestHash = RequestHash.sha256(projectId + "|" + taskId + "|" + body);
        var replay = idempotencyService.beginOrReplay(workspaceId, idempotencyKey, requestHash, CommentView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireTask(workspaceId, projectId, taskId);

        UUID commentId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        jdbcClient
                .sql("""
                        INSERT INTO task_comment (id, workspace_id, task_id, author_id, body, created_at)
                        VALUES (:id, :workspaceId, :taskId, :authorId, :body, :createdAt)
                        """)
                .param("id", commentId)
                .param("workspaceId", workspaceId)
                .param("taskId", taskId)
                .param("authorId", user.id())
                .param("body", body)
                .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .update();

        CommentView result = new CommentView(commentId, user.displayName(), body, createdAt);
        taskEventRecorder.record(
                workspaceId,
                user.id(),
                taskId,
                "TASK_COMMENTED",
                "task.commented.v1",
                Map.of("commentId", commentId),
                createdAt);
        idempotencyService.complete(workspaceId, idempotencyKey, 201, result);
        return result;
    }

    @Transactional(readOnly = true)
    public CommentListView comments(AuthenticatedUser user, UUID workspaceId, UUID projectId, UUID taskId) {
        workspaceAccessService.requireMembership(user.id(), workspaceId);
        requireTask(workspaceId, projectId, taskId);
        List<CommentView> items = jdbcClient
                .sql("""
                        SELECT task_comment.id, author.display_name, task_comment.body, task_comment.created_at
                        FROM task_comment
                        JOIN app_user author ON author.id = task_comment.author_id
                        WHERE task_comment.workspace_id = :workspaceId AND task_comment.task_id = :taskId
                        ORDER BY task_comment.created_at, task_comment.id
                        """)
                .param("workspaceId", workspaceId)
                .param("taskId", taskId)
                .query((row, number) -> new CommentView(
                        row.getObject("id", UUID.class),
                        row.getString("display_name"),
                        row.getString("body"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
        return new CommentListView(items);
    }

    private void requireTask(UUID workspaceId, UUID projectId, UUID taskId) {
        boolean exists = jdbcClient
                .sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM task
                            WHERE id = :taskId AND workspace_id = :workspaceId AND project_id = :projectId
                        )
                        """)
                .param("taskId", taskId)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .query(Boolean.class)
                .single();
        if (!exists) {
            throw new ResourceNotFoundException("The task does not exist.");
        }
    }

    public record AssigneeView(UUID id, String name, String email) {}

    public record AssignmentView(UUID id, AssigneeView assignee, long version, Instant updatedAt) {}

    public record CommentView(UUID id, String author, String body, Instant createdAt) {}

    public record CommentListView(List<CommentView> items) {}
}
