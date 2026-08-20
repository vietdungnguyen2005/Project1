package dev.vcore.task.application;

import dev.vcore.identity.application.AuthenticatedUser;
import dev.vcore.shared.web.ApiConflictException;
import dev.vcore.shared.web.ResourceNotFoundException;
import dev.vcore.task.domain.TaskEntity;
import dev.vcore.task.infrastructure.TaskRepository;
import dev.vcore.workspace.application.WorkspaceAccessService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskEditService {

    private final TaskRepository taskRepository;
    private final WorkspaceAccessService workspaceAccessService;
    private final IdempotencyService idempotencyService;
    private final TaskEventRecorder taskEventRecorder;

    public TaskEditService(
            TaskRepository taskRepository,
            WorkspaceAccessService workspaceAccessService,
            IdempotencyService idempotencyService,
            TaskEventRecorder taskEventRecorder) {
        this.taskRepository = taskRepository;
        this.workspaceAccessService = workspaceAccessService;
        this.idempotencyService = idempotencyService;
        this.taskEventRecorder = taskEventRecorder;
    }

    @Transactional
    public EditTaskResult rename(
            AuthenticatedUser user,
            UUID workspaceId,
            UUID projectId,
            UUID taskId,
            String idempotencyKey,
            EditTaskCommand command) {
        workspaceAccessService.requireMutationAccess(user.id(), workspaceId);
        String normalizedTitle = command.title().strip();
        String requestHash =
                RequestHash.sha256(projectId + "|" + taskId + "|" + normalizedTitle + "|" + command.expectedVersion());
        var replay = idempotencyService.beginOrReplay(workspaceId, idempotencyKey, requestHash, EditTaskResult.class);
        if (replay.isPresent()) {
            return replay.get();
        }

        TaskEntity task = taskRepository
                .findByIdAndWorkspaceIdAndProjectId(taskId, workspaceId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("The task does not exist."));
        if (task.version() != command.expectedVersion()) {
            throw new ApiConflictException(
                    "The task changed after it was loaded. Current version is " + task.version() + ".");
        }

        String previousTitle = task.title();
        Instant changedAt = Instant.now();
        task.rename(normalizedTitle, user.id(), changedAt);
        taskRepository.saveAndFlush(task);

        EditTaskResult result = new EditTaskResult(task.id(), task.title(), task.version(), task.updatedAt());
        taskEventRecorder.record(
                workspaceId,
                user.id(),
                task.id(),
                "TASK_RENAMED",
                "task.renamed.v1",
                Map.of("previousTitle", previousTitle, "title", task.title(), "version", task.version()),
                changedAt);
        idempotencyService.complete(workspaceId, idempotencyKey, 200, result);
        return result;
    }

    public record EditTaskCommand(String title, long expectedVersion) {}

    public record EditTaskResult(UUID id, String title, long version, Instant updatedAt) {}
}
