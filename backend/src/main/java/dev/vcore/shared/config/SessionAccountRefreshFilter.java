package dev.vcore.shared.config;

import dev.vcore.identity.application.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class SessionAccountRefreshFilter extends OncePerRequestFilter {
    private final JdbcClient jdbc;

    SessionAccountRefreshFilter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && !(authentication
                        instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)) {
            var account = jdbc.sql(
                            "SELECT u.id,u.email,u.display_name,a.role FROM auth_account a JOIN app_user u ON u.id=a.user_id WHERE a.username=:name AND a.enabled=TRUE")
                    .param("name", authentication.getName())
                    .query((rs, row) -> new Account(
                            new AuthenticatedUser(
                                    rs.getObject("id", UUID.class),
                                    rs.getString("email"),
                                    rs.getString("display_name")),
                            rs.getString("role")))
                    .optional();
            if (account.isEmpty()) {
                var session = request.getSession(false);
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
            } else {
                var current = account.get();
                SecurityContextHolder.getContext()
                        .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                                current.user(), null, List.of(new SimpleGrantedAuthority("ROLE_" + current.role()))));
            }
        }
        chain.doFilter(request, response);
    }

    private record Account(AuthenticatedUser user, String role) {}
}
