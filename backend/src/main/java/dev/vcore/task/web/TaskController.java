package dev.vcore.task.web;

import dev.vcore.identity.application.AuthenticatedUser;
import dev.vcore.task.application.TaskCreateService;
import dev.vcore.task.application.TaskCreateService.CreateTaskCommand;
import dev.vcore.task.application.TaskCreateService.CreateTaskResult;
import dev.vcore.task.application.TaskEditService;
import dev.vcore.task.application.TaskEditService.EditTaskCommand;
import dev.vcore.task.application.TaskEditService.EditTaskResult;
import dev.vcore.task.application.TaskMoveService;
import dev.vcore.task.application.TaskMoveService.MoveTaskCommand;
import dev.vcore.task.application.TaskMoveService.MoveTaskResult;
import dev.vcore.task.application.TaskQueryService;
import dev.vcore.task.application.TaskQueryService.TaskListView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/tasks")
public class TaskController {

    private final TaskQueryService taskQueryService;
    private final TaskMoveService taskMoveService;
    private final TaskEditService taskEditService;
    private final TaskCreateService taskCreateService;

    public TaskController(
            TaskQueryService taskQueryService,
            TaskMoveService taskMoveService,
            TaskEditService taskEditService,
            TaskCreateService taskCreateService) {
        this.taskQueryService = taskQueryService;
        this.taskMoveService = taskMoveService;
        this.taskEditService = taskEditService;
        this.taskCreateService = taskCreateService;
    }

    @GetMapping
    TaskListView listTasks(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId) {
        return taskQueryService.listBoardTasks(user, workspaceId, projectId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CreateTaskResult createTask(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 120) String idempotencyKey,
            @Valid @RequestBody CreateTaskRequest request) {
        return taskCreateService.create(
                user,
                workspaceId,
                projectId,
                idempotencyKey,
                new CreateTaskCommand(
                        request.title(), request.columnId(), request.priority(), request.points(), request.tags()));
    }

    @PostMapping("/{taskId}/moves")
    MoveTaskResult moveTask(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID taskId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 120) String idempotencyKey,
            @Valid @RequestBody MoveTaskRequest request) {
        return taskMoveService.move(
                user,
                workspaceId,
                projectId,
                taskId,
                idempotencyKey,
                new MoveTaskCommand(request.targetColumnId(), request.expectedVersion(), request.position()));
    }

    @PatchMapping("/{taskId}")
    EditTaskResult editTask(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID taskId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 120) String idempotencyKey,
            @Valid @RequestBody EditTaskRequest request) {
        return taskEditService.rename(
                user,
                workspaceId,
                projectId,
                taskId,
                idempotencyKey,
                new EditTaskCommand(request.title(), request.expectedVersion()));
    }

    record MoveTaskRequest(
            @NotNull UUID targetColumnId,
            @Min(0) long expectedVersion,
            @Min(0) long position) {}

    record EditTaskRequest(
            @NotBlank @Size(max = 240) String title, @Min(0) long expectedVersion) {}

    record CreateTaskRequest(
            @NotBlank @Size(max = 240) String title,
            @NotNull UUID columnId,

            @NotBlank @Pattern(regexp = "critical|high|medium|low") String priority,

            @Min(0) @jakarta.validation.constraints.Max(100) int points,
            @NotNull @Size(max = 10) List<@NotBlank @Size(max = 40) String> tags) {}
}
