package dev.vcore.task.web;

import dev.vcore.identity.application.AuthenticatedUser;
import dev.vcore.task.application.TaskCollaborationService;
import dev.vcore.task.application.TaskCollaborationService.AssignmentView;
import dev.vcore.task.application.TaskCollaborationService.CommentListView;
import dev.vcore.task.application.TaskCollaborationService.CommentView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/tasks/{taskId}")
public class TaskCollaborationController {

    private final TaskCollaborationService collaborationService;

    public TaskCollaborationController(TaskCollaborationService collaborationService) {
        this.collaborationService = collaborationService;
    }

    @PatchMapping("/assignee")
    AssignmentView assign(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID taskId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 120) String idempotencyKey,
            @Valid @RequestBody AssignmentRequest request) {
        return collaborationService.assign(
                user, workspaceId, projectId, taskId, idempotencyKey, request.assigneeId(), request.expectedVersion());
    }

    @GetMapping("/comments")
    CommentListView comments(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID taskId) {
        return collaborationService.comments(user, workspaceId, projectId, taskId);
    }

    @PostMapping("/comments")
    @ResponseStatus(HttpStatus.CREATED)
    CommentView comment(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID taskId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 120) String idempotencyKey,
            @Valid @RequestBody CommentRequest request) {
        return collaborationService.comment(user, workspaceId, projectId, taskId, idempotencyKey, request.body());
    }

    record AssignmentRequest(
            @NotNull UUID assigneeId, @Min(0) long expectedVersion) {}

    record CommentRequest(@NotBlank @Size(max = 2000) String body) {}
}
