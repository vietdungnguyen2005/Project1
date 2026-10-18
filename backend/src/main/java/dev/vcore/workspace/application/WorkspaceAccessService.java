package dev.vcore.workspace.application;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceAccessService {

    private final JdbcClient jdbcClient;

    public WorkspaceAccessService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void requireMembership(UUID userId, UUID workspaceId) {
        requireAccess(userId, workspaceId, false);
    }

    public void requireMutationAccess(UUID userId, UUID workspaceId) {
        requireAccess(userId, workspaceId, true);
    }

    public void requireAdministrationAccess(UUID userId, UUID workspaceId) {
        String role = databaseRole(userId, workspaceId);
        if (!"OWNER".equals(role) && !"ADMIN".equals(role)) {
            throw new AccessDeniedException("Workspace administration requires an owner or admin role.");
        }
    }

    private void requireAccess(UUID userId, UUID workspaceId, boolean mutation) {
        String role = databaseRole(userId, workspaceId);

        if (role == null || (mutation && "VIEWER".equals(role))) {
            throw new AccessDeniedException("The user does not have access to this workspace operation.");
        }
    }

    private String databaseRole(UUID userId, UUID workspaceId) {
        return jdbcClient
                .sql("""
                            SELECT role
                            FROM workspace_membership
                            WHERE workspace_id = :workspaceId AND user_id = :userId
                            """)
                .param("workspaceId", workspaceId)
                .param("userId", userId)
                .query(String.class)
                .optional()
                .orElse(null);
    }
}
