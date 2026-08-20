package dev.vcore.workspace.application;

import dev.vcore.identity.application.AuthenticatedUser;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionQueryService {

    private final JdbcClient jdbcClient;

    public SessionQueryService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(readOnly = true)
    public SessionView getSession(AuthenticatedUser user) {
        List<WorkspaceView> workspaces = jdbcClient
                .sql("""
                        SELECT w.id, w.name, w.slug, m.role
                        FROM workspace_membership m
                        JOIN workspace w ON w.id = m.workspace_id
                        WHERE m.user_id = :userId
                        ORDER BY w.name, w.id
                        """)
                .param("userId", user.id())
                .query((resultSet, rowNumber) -> new WorkspaceView(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("name"),
                        resultSet.getString("slug"),
                        resultSet.getString("role")))
                .list();

        return new SessionView(new UserView(user.id(), user.email(), user.displayName()), workspaces);
    }

    public record SessionView(UserView user, List<WorkspaceView> workspaces) {}

    public record UserView(UUID id, String email, String name) {}

    public record WorkspaceView(UUID id, String name, String slug, String role) {}
}
