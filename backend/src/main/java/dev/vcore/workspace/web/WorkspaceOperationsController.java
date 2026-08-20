package dev.vcore.workspace.web;

import dev.vcore.identity.application.AuthenticatedUser;
import dev.vcore.workspace.application.WorkspaceOperationsService;
import dev.vcore.workspace.application.WorkspaceOperationsService.InvitationView;
import dev.vcore.workspace.application.WorkspaceOperationsService.WorkspaceOverview;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
@RequestMapping("/api/workspaces/{workspaceId}")
public class WorkspaceOperationsController {

    private final WorkspaceOperationsService operationsService;

    public WorkspaceOperationsController(WorkspaceOperationsService operationsService) {
        this.operationsService = operationsService;
    }

    @GetMapping("/overview")
    WorkspaceOverview overview(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID workspaceId) {
        return operationsService.overview(user, workspaceId);
    }

    @PostMapping("/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    InvitationView invite(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID workspaceId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 120) String idempotencyKey,
            @Valid @RequestBody InvitationRequest request) {
        return operationsService.invite(user, workspaceId, idempotencyKey, request.email(), request.role());
    }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    WorkspaceOperationsService.ProjectView createProject(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID workspaceId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 120) String idempotencyKey,
            @Valid @RequestBody CreateProjectRequest request) {
        return operationsService.createProject(
                user,
                workspaceId,
                idempotencyKey,
                request.name(),
                request.key(),
                request.description(),
                request.sprintName(),
                request.sprintGoal());
    }

    @PatchMapping("/projects/{projectId}/workflow-columns/{columnId}")
    WorkspaceOperationsService.ColumnView updateWipLimit(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID columnId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 120) String idempotencyKey,
            @Valid @RequestBody UpdateWipRequest request) {
        return operationsService.updateWipLimit(
                user, workspaceId, projectId, columnId, idempotencyKey, request.wipLimit(), request.expectedVersion());
    }

    record InvitationRequest(
            @NotBlank @Email @Size(max = 320) String email,

            @NotBlank @Pattern(regexp = "ADMIN|MEMBER|VIEWER") String role) {}

    record CreateProjectRequest(
            @NotBlank @Size(max = 160) String name,

            @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9]{1,11}") String key,

            @NotNull @Size(max = 2000) String description,
            @NotBlank @Size(max = 120) String sprintName,
            @NotBlank @Size(max = 2000) String sprintGoal) {}

    record UpdateWipRequest(
            @NotNull @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(100) Integer wipLimit,

            @jakarta.validation.constraints.Min(0) long expectedVersion) {}
}
