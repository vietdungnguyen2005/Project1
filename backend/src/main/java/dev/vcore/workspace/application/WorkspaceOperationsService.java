package dev.vcore.workspace.application;

import dev.vcore.identity.application.AuthenticatedUser;
import dev.vcore.shared.web.ApiConflictException;
import dev.vcore.shared.web.ResourceNotFoundException;
import dev.vcore.task.application.IdempotencyService;
import dev.vcore.task.application.RequestHash;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceOperationsService {

    private final JdbcClient jdbcClient;
    private final WorkspaceAccessService workspaceAccessService;
    private final IdempotencyService idempotencyService;
    private final WorkspaceEventRecorder eventRecorder;

    public WorkspaceOperationsService(
            JdbcClient jdbcClient,
            WorkspaceAccessService workspaceAccessService,
            IdempotencyService idempotencyService,
            WorkspaceEventRecorder eventRecorder) {
        this.jdbcClient = jdbcClient;
        this.workspaceAccessService = workspaceAccessService;
        this.idempotencyService = idempotencyService;
        this.eventRecorder = eventRecorder;
    }

    @Transactional(readOnly = true)
    public WorkspaceOverview overview(AuthenticatedUser user, UUID workspaceId) {
        workspaceAccessService.requireMembership(user.id(), workspaceId);
        return new WorkspaceOverview(projects(workspaceId), members(workspaceId), invitations(workspaceId));
    }

    @Transactional
    public InvitationView invite(
            AuthenticatedUser user, UUID workspaceId, String idempotencyKey, String rawEmail, String role) {
        workspaceAccessService.requireAdministrationAccess(user.id(), workspaceId);
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        String requestHash = RequestHash.sha256(workspaceId + "|" + email + "|" + role);
        var replay = idempotencyService.beginOrReplay(workspaceId, idempotencyKey, requestHash, InvitationView.class);
        if (replay.isPresent()) {
            return replay.get();
        }

        UUID invitationId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(7 * 24 * 60 * 60);
        jdbcClient
                .sql("""
                        INSERT INTO workspace_invitation (
                            id, workspace_id, email, role, status, invited_by, expires_at
                        ) VALUES (
                            :id, :workspaceId, :email, :role, 'PENDING', :invitedBy, :expiresAt
                        )
                        """)
                .param("id", invitationId)
                .param("workspaceId", workspaceId)
                .param("email", email)
                .param("role", role)
                .param("invitedBy", user.id())
                .param("expiresAt", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                .update();

        InvitationView result = new InvitationView(invitationId, email, role, "PENDING", expiresAt);
        eventRecorder.record(
                workspaceId,
                user.id(),
                "WORKSPACE",
                workspaceId,
                "INVITATION_CREATED",
                "workspace.invitation-created.v1",
                java.util.Map.of("email", email, "role", role),
                Instant.now());
        idempotencyService.complete(workspaceId, idempotencyKey, 201, result);
        return result;
    }

    @Transactional
    public ProjectView createProject(
            AuthenticatedUser user,
            UUID workspaceId,
            String idempotencyKey,
            String rawName,
            String rawKey,
            String rawDescription,
            String rawSprintName,
            String rawSprintGoal) {
        workspaceAccessService.requireAdministrationAccess(user.id(), workspaceId);
        String name = rawName.strip();
        String key = rawKey.strip().toUpperCase(Locale.ROOT);
        String description = rawDescription.strip();
        String sprintName = rawSprintName.strip();
        String sprintGoal = rawSprintGoal.strip();
        String requestHash = RequestHash.sha256(
                workspaceId + "|" + name + "|" + key + "|" + description + "|" + sprintName + "|" + sprintGoal);
        var replay = idempotencyService.beginOrReplay(workspaceId, idempotencyKey, requestHash, ProjectView.class);
        if (replay.isPresent()) {
            return replay.get();
        }

        UUID projectId = UUID.randomUUID();
        UUID sprintId = UUID.randomUUID();
        jdbcClient
                .sql("""
                        INSERT INTO project (id, workspace_id, name, project_key, description)
                        VALUES (:id, :workspaceId, :name, :key, :description)
                        """)
                .param("id", projectId)
                .param("workspaceId", workspaceId)
                .param("name", name)
                .param("key", key)
                .param("description", description)
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO sprint (id, workspace_id, project_id, name, goal, status)
                        VALUES (:id, :workspaceId, :projectId, :name, :goal, 'ACTIVE')
                        """)
                .param("id", sprintId)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("name", sprintName)
                .param("goal", sprintGoal)
                .update();

        createColumn(workspaceId, projectId, "Backlog", "BACKLOG", 0, null);
        createColumn(workspaceId, projectId, "In progress", "IN_PROGRESS", 1, 3);
        createColumn(workspaceId, projectId, "Review", "IN_PROGRESS", 2, 2);
        createColumn(workspaceId, projectId, "Done", "DONE", 3, null);

        ProjectView result = new ProjectView(
                projectId,
                name,
                key,
                description,
                new SprintView(sprintId, sprintName, sprintGoal, "ACTIVE"),
                columns(workspaceId, projectId));
        eventRecorder.record(
                workspaceId,
                user.id(),
                "PROJECT",
                projectId,
                "PROJECT_CREATED",
                "project.created.v1",
                java.util.Map.of("key", key, "sprintId", sprintId),
                Instant.now());
        idempotencyService.complete(workspaceId, idempotencyKey, 201, result);
        return result;
    }

    @Transactional
    public ColumnView updateWipLimit(
            AuthenticatedUser user,
            UUID workspaceId,
            UUID projectId,
            UUID columnId,
            String idempotencyKey,
            Integer wipLimit,
            long expectedVersion) {
        workspaceAccessService.requireAdministrationAccess(user.id(), workspaceId);
        String requestHash = RequestHash.sha256(projectId + "|" + columnId + "|" + wipLimit + "|" + expectedVersion);
        var replay = idempotencyService.beginOrReplay(workspaceId, idempotencyKey, requestHash, ColumnView.class);
        if (replay.isPresent()) {
            return replay.get();
        }

        ColumnView current = jdbcClient
                .sql("""
                        SELECT id, name, category, position, wip_limit, version
                        FROM workflow_column
                        WHERE workspace_id = :workspaceId AND project_id = :projectId AND id = :columnId
                        FOR UPDATE
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("columnId", columnId)
                .query((row, number) -> new ColumnView(
                        row.getObject("id", UUID.class),
                        row.getString("name"),
                        row.getString("category"),
                        row.getInt("position"),
                        row.getObject("wip_limit", Integer.class),
                        row.getLong("version")))
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("The workflow column does not exist."));
        if (current.version() != expectedVersion) {
            throw new ApiConflictException(
                    "The workflow changed after it was loaded. Current version is " + current.version() + ".");
        }

        jdbcClient
                .sql("""
                        UPDATE workflow_column
                        SET wip_limit = :wipLimit, version = version + 1
                        WHERE workspace_id = :workspaceId AND project_id = :projectId AND id = :columnId
                        """)
                .param("wipLimit", wipLimit)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("columnId", columnId)
                .update();
        ColumnView result = new ColumnView(
                current.id(), current.name(), current.category(), current.position(), wipLimit, current.version() + 1);
        eventRecorder.record(
                workspaceId,
                user.id(),
                "PROJECT",
                projectId,
                "WORKFLOW_WIP_UPDATED",
                "workflow.wip-updated.v1",
                java.util.Map.of("columnId", columnId, "wipLimit", wipLimit, "version", current.version() + 1),
                Instant.now());
        idempotencyService.complete(workspaceId, idempotencyKey, 200, result);
        return result;
    }

    private void createColumn(
            UUID workspaceId, UUID projectId, String name, String category, int position, Integer wipLimit) {
        jdbcClient
                .sql("""
                        INSERT INTO workflow_column (
                            id, workspace_id, project_id, name, category, position, wip_limit
                        ) VALUES (
                            :id, :workspaceId, :projectId, :name, :category, :position, :wipLimit
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("name", name)
                .param("category", category)
                .param("position", position)
                .param("wipLimit", wipLimit)
                .update();
    }

    private List<MemberView> members(UUID workspaceId) {
        return jdbcClient
                .sql("""
                        SELECT app_user.id, app_user.display_name, app_user.email, membership.role
                        FROM workspace_membership membership
                        JOIN app_user ON app_user.id = membership.user_id
                        WHERE membership.workspace_id = :workspaceId
                        ORDER BY CASE membership.role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END,
                                 app_user.display_name
                        """)
                .param("workspaceId", workspaceId)
                .query((row, number) -> new MemberView(
                        row.getObject("id", UUID.class),
                        row.getString("display_name"),
                        row.getString("email"),
                        row.getString("role")))
                .list();
    }

    private List<InvitationView> invitations(UUID workspaceId) {
        return jdbcClient
                .sql("""
                        SELECT id, email, role, status, expires_at
                        FROM workspace_invitation
                        WHERE workspace_id = :workspaceId AND status = 'PENDING'
                        ORDER BY created_at DESC
                        """)
                .param("workspaceId", workspaceId)
                .query((row, number) -> new InvitationView(
                        row.getObject("id", UUID.class),
                        row.getString("email"),
                        row.getString("role"),
                        row.getString("status"),
                        row.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private List<ProjectView> projects(UUID workspaceId) {
        return jdbcClient
                .sql("""
                        SELECT project.id, project.name, project.project_key, project.description,
                               sprint.id AS sprint_id, sprint.name AS sprint_name,
                               sprint.goal AS sprint_goal, sprint.status AS sprint_status
                        FROM project
                        LEFT JOIN sprint
                          ON sprint.workspace_id = project.workspace_id
                         AND sprint.project_id = project.id
                         AND sprint.status = 'ACTIVE'
                        WHERE project.workspace_id = :workspaceId AND NOT project.archived
                        ORDER BY project.created_at
                        """)
                .param("workspaceId", workspaceId)
                .query((row, number) -> {
                    UUID projectId = row.getObject("id", UUID.class);
                    UUID sprintId = row.getObject("sprint_id", UUID.class);
                    SprintView sprint = sprintId == null
                            ? null
                            : new SprintView(
                                    sprintId,
                                    row.getString("sprint_name"),
                                    row.getString("sprint_goal"),
                                    row.getString("sprint_status"));
                    return new ProjectView(
                            projectId,
                            row.getString("name"),
                            row.getString("project_key"),
                            row.getString("description"),
                            sprint,
                            columns(workspaceId, projectId));
                })
                .list();
    }

    private List<ColumnView> columns(UUID workspaceId, UUID projectId) {
        return jdbcClient
                .sql("""
                        SELECT id, name, category, position, wip_limit, version
                        FROM workflow_column
                        WHERE workspace_id = :workspaceId AND project_id = :projectId
                        ORDER BY position
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .query((row, number) -> new ColumnView(
                        row.getObject("id", UUID.class),
                        row.getString("name"),
                        row.getString("category"),
                        row.getInt("position"),
                        row.getObject("wip_limit", Integer.class),
                        row.getLong("version")))
                .list();
    }

    public record WorkspaceOverview(
            List<ProjectView> projects, List<MemberView> members, List<InvitationView> invitations) {}

    public record ProjectView(
            UUID id, String name, String key, String description, SprintView activeSprint, List<ColumnView> columns) {}

    public record SprintView(UUID id, String name, String goal, String status) {}

    public record ColumnView(UUID id, String name, String category, int position, Integer wipLimit, long version) {}

    public record MemberView(UUID id, String name, String email, String role) {}

    public record InvitationView(UUID id, String email, String role, String status, Instant expiresAt) {}
}
