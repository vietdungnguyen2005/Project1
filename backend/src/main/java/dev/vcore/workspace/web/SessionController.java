package dev.vcore.workspace.web;

import dev.vcore.identity.application.AuthenticatedUser;
import dev.vcore.workspace.application.SessionQueryService;
import dev.vcore.workspace.application.SessionQueryService.SessionView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/session")
public class SessionController {

    private final SessionQueryService sessionQueryService;

    public SessionController(SessionQueryService sessionQueryService) {
        this.sessionQueryService = sessionQueryService;
    }

    @GetMapping
    SessionView getSession(@AuthenticationPrincipal AuthenticatedUser user) {
        return sessionQueryService.getSession(user);
    }
}
