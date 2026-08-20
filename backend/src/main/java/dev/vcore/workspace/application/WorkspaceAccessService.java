package dev.vcore.workspace.application;

import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceAccessService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceAccessService.class);
    private static final Duration MEMBERSHIP_CACHE_TTL = Duration.ofMinutes(5);

    private final JdbcClient jdbcClient;
    private final StringRedisTemplate redisTemplate;

    public WorkspaceAccessService(JdbcClient jdbcClient, StringRedisTemplate redisTemplate) {
        this.jdbcClient = jdbcClient;
        this.redisTemplate = redisTemplate;
    }

    public void requireMembership(UUID userId, UUID workspaceId) {
        requireAccess(userId, workspaceId, false);
    }

    public void requireMutationAccess(UUID userId, UUID workspaceId) {
        requireAccess(userId, workspaceId, true);
    }

    public void requireAdministrationAccess(UUID userId, UUID workspaceId) {
        String role = resolveRole(userId, workspaceId);
        if (!"OWNER".equals(role) && !"ADMIN".equals(role)) {
            throw new AccessDeniedException("Workspace administration requires an owner or admin role.");
        }
    }

    private void requireAccess(UUID userId, UUID workspaceId, boolean mutation) {
        String role = resolveRole(userId, workspaceId);

        if (role == null || (mutation && "VIEWER".equals(role))) {
            throw new AccessDeniedException("The user does not have access to this workspace operation.");
        }
    }

    private String resolveRole(UUID userId, UUID workspaceId) {
        String role = cachedRole(userId, workspaceId);
        if (role == null) {
            role = jdbcClient
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
            cacheRole(userId, workspaceId, role);
        }

        return role;
    }

    private String cachedRole(UUID userId, UUID workspaceId) {
        try {
            return redisTemplate.opsForValue().get(cacheKey(userId, workspaceId));
        } catch (DataAccessException exception) {
            LOGGER.debug("Redis membership cache read failed; PostgreSQL remains authoritative", exception);
            return null;
        }
    }

    private void cacheRole(UUID userId, UUID workspaceId, String role) {
        if (role == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(cacheKey(userId, workspaceId), role, MEMBERSHIP_CACHE_TTL);
        } catch (DataAccessException exception) {
            LOGGER.debug("Redis membership cache write failed; request remains valid", exception);
        }
    }

    private String cacheKey(UUID userId, UUID workspaceId) {
        return "vcore:membership:" + workspaceId + ":" + userId;
    }
}
