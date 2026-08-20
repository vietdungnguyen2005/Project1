package dev.vcore.task.application;

import dev.vcore.identity.application.AuthenticatedUser;
import dev.vcore.shared.web.ApiConflictException;
import dev.vcore.shared.web.ResourceNotFoundException;
import dev.vcore.task.domain.TaskEntity;
import dev.vcore.task.domain.WorkflowColumnEntity;
import dev.vcore.task.infrastructure.TaskRepository;
import dev.vcore.task.infrastructure.WorkflowColumnRepository;
import dev.vcore.workspace.application.WorkspaceAccessService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskMoveService {

    private final TaskRepository taskRepository;
    private final WorkflowColumnRepository columnRepository;
    private final WorkspaceAccessService workspaceAccessService;
    private final IdempotencyService idempotencyService;
    private final TaskEventRecorder taskEventRecorder;

    public TaskMoveService(
            TaskRepository taskRepository,
            WorkflowColumnRepository columnRepository,
            WorkspaceAccessService workspaceAccessService,
            IdempotencyService idempotencyService,
            TaskEventRecorder taskEventRecorder) {
        this.taskRepository = taskRepository;
        this.columnRepository = columnRepository;
        this.workspaceAccessService = workspaceAccessService;
        this.idempotencyService = idempotencyService;
        this.taskEventRecorder = taskEventRecorder;
    }

    @Transactional
    public MoveTaskResult move(
            AuthenticatedUser user,
            UUID workspaceId,
            UUID projectId,
            UUID taskId,
            String idempotencyKey,
            MoveTaskCommand command) {
        workspaceAccessService.requireMutationAccess(user.id(), workspaceId);
        String requestHash = requestHash(projectId, taskId, command);
        var replay = idempotencyService.beginOrReplay(workspaceId, idempotencyKey, requestHash, MoveTaskResult.class);
        if (replay.isPresent()) {
            return replay.get();
        }

        WorkflowColumnEntity targetColumn = columnRepository
                .findForMove(command.targetColumnId(), workspaceId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("The target workflow column does not exist."));
        TaskEntity task = taskRepository
                .findByIdAndWorkspaceIdAndProjectId(taskId, workspaceId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("The task does not exist."));

        if (task.version() != command.expectedVersion()) {
            throw new ApiConflictException(
                    "The task changed after it was loaded. Current version is " + task.version() + ".");
        }

        UUID sourceColumnId = task.columnId();
        if (!sourceColumnId.equals(targetColumn.id()) && targetColumn.wipLimit() != null) {
            long currentWork =
                    taskRepository.countByWorkspaceIdAndProjectIdAndColumnId(workspaceId, projectId, targetColumn.id());
            if (currentWork >= targetColumn.wipLimit()) {
                throw new ApiConflictException(
                        "The target column WIP limit of " + targetColumn.wipLimit() + " has been reached.");
            }
        }

        Instant changedAt = Instant.now();
        task.moveTo(targetColumn.id(), command.position(), user.id(), changedAt);
        taskRepository.saveAndFlush(task);

        MoveTaskResult result = new MoveTaskResult(
                task.id(),
                targetColumn.id(),
                targetColumn.clientStatus(),
                task.position(),
                task.version(),
                task.updatedAt());
        taskEventRecorder.record(
                workspaceId,
                user.id(),
                task.id(),
                "TASK_MOVED",
                "task.moved.v1",
                Map.of(
                        "fromColumnId", sourceColumnId,
                        "toColumnId", result.columnId(),
                        "version", result.version()),
                result.updatedAt());
        idempotencyService.complete(workspaceId, idempotencyKey, 200, result);
        return result;
    }

    private String requestHash(UUID projectId, UUID taskId, MoveTaskCommand command) {
        String canonical = projectId + "|" + taskId + "|" + command.targetColumnId() + "|" + command.expectedVersion()
                + "|" + command.position();
        return RequestHash.sha256(canonical);
    }

    public record MoveTaskCommand(UUID targetColumnId, long expectedVersion, long position) {}

    public record MoveTaskResult(
            UUID id, UUID columnId, String status, long position, long version, Instant updatedAt) {}
}
