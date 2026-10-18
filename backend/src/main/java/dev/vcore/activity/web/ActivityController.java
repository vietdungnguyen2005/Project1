package dev.vcore.activity.web;

import dev.vcore.activity.application.ActivityQueryService;
import dev.vcore.activity.application.ActivityQueryService.ActivityListView;
import dev.vcore.activity.application.SseEventHub;
import dev.vcore.activity.application.SseSubscriptionAccess;
import dev.vcore.identity.application.AuthenticatedUser;
import dev.vcore.workspace.application.WorkspaceAccessService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}")
public class ActivityController {

    private final ActivityQueryService activityQueryService;
    private final WorkspaceAccessService workspaceAccessService;
    private final SseEventHub eventHub;

    public ActivityController(
            ActivityQueryService activityQueryService,
            WorkspaceAccessService workspaceAccessService,
            SseEventHub eventHub) {
        this.activityQueryService = activityQueryService;
        this.workspaceAccessService = workspaceAccessService;
        this.eventHub = eventHub;
    }

    @GetMapping("/activities")
    ActivityListView listActivities(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID workspaceId,
            @RequestParam(defaultValue = "50") int limit) {
        return activityQueryService.list(user, workspaceId, limit);
    }

    @GetMapping(path = "/events", produces = "text/event-stream")
    SseEmitter subscribe(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID workspaceId,
            @org.springframework.security.core.annotation.CurrentSecurityContext(expression = "authentication")
                    Authentication authentication,
            HttpServletRequest request) {
        workspaceAccessService.requireMembership(user.id(), workspaceId);
        boolean demo = authentication instanceof AnonymousAuthenticationToken;
        var session = demo ? null : request.getSession(false);
        return eventHub.subscribe(
                workspaceId,
                new SseSubscriptionAccess.Identity(user.id(), session == null ? "" : session.getId(), demo));
    }
}
