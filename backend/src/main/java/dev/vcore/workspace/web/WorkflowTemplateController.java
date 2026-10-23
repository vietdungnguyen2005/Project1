package dev.vcore.workspace.web;

import dev.vcore.identity.application.AuthenticatedUser;
import dev.vcore.workspace.application.WorkflowTemplateService;
import dev.vcore.workspace.application.WorkflowTemplateService.Definition;
import dev.vcore.workspace.application.WorkflowTemplateService.TemplateView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
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
public class WorkflowTemplateController {
    private final WorkflowTemplateService templates;

    public WorkflowTemplateController(WorkflowTemplateService templates) {
        this.templates = templates;
    }

    @GetMapping("/workflow-presets")
    List<Definition> presets(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID workspaceId) {
        return templates.presets(user, workspaceId);
    }

    @GetMapping("/workflow-templates")
    List<TemplateView> list(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID workspaceId) {
        return templates.list(user, workspaceId);
    }

    @GetMapping("/workflow-templates/{templateId}")
    TemplateView get(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID workspaceId,
            @PathVariable UUID templateId) {
        return templates.get(user, workspaceId, templateId);
    }

    @PostMapping("/workflow-templates")
    @ResponseStatus(HttpStatus.CREATED)
    TemplateView publish(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID workspaceId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 120) String key,
            @Valid @RequestBody Definition request) {
        return templates.publish(user, workspaceId, key, request);
    }
}
