package dev.vcore.activity.application;

import dev.vcore.shared.config.SecurityProperties;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class SseSubscriptionAccess {
    private static final UUID DEMO_VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private final JdbcClient jdbc;
    private final SecurityProperties security;

    public SseSubscriptionAccess(JdbcClient jdbc, SecurityProperties security) {
        this.jdbc = jdbc;
        this.security = security;
    }

    public boolean allowed(UUID workspaceId, Identity identity) {
        boolean publicDemo = identity.demo() && security.demoReadEnabled() && DEMO_VIEWER.equals(identity.userId());
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM workspace_membership membership
                    WHERE membership.workspace_id=:workspace AND membership.user_id=:user
                    AND (
                        :publicDemo
                        OR EXISTS (
                            SELECT 1 FROM auth_account account
                            JOIN spring_session session ON session.principal_name=account.username
                            WHERE account.user_id=:user AND account.enabled=TRUE
                            AND session.session_id=:session
                            AND session.expiry_time > EXTRACT(EPOCH FROM clock_timestamp()) * 1000
                        )
                    )
                )
                """)
                .param("workspace", workspaceId)
                .param("user", identity.userId())
                .param("publicDemo", publicDemo)
                .param("session", identity.sessionId())
                .query(Boolean.class)
                .single();
    }

    public record Identity(UUID userId, String sessionId, boolean demo) {
        public Identity {
            java.util.Objects.requireNonNull(userId);
            sessionId = sessionId == null ? "" : sessionId;
        }
    }
}
