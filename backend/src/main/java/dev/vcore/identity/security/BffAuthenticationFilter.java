package dev.vcore.identity.security;

import dev.vcore.identity.application.UserIdentityLookup;
import dev.vcore.shared.config.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class BffAuthenticationFilter extends OncePerRequestFilter {

    static final String BFF_KEY_HEADER = "X-VCore-Bff-Key";
    static final String USER_EMAIL_HEADER = "X-VCore-User-Email";

    private final SecurityProperties securityProperties;
    private final UserIdentityLookup userIdentityLookup;

    public BffAuthenticationFilter(SecurityProperties securityProperties, UserIdentityLookup userIdentityLookup) {
        this.securityProperties = securityProperties;
        this.userIdentityLookup = userIdentityLookup;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String suppliedKey = request.getHeader(BFF_KEY_HEADER);
        String email = request.getHeader(USER_EMAIL_HEADER);

        if (isTrusted(suppliedKey) && email != null && !email.isBlank()) {
            userIdentityLookup.findByEmail(email).ifPresent(user -> {
                var authentication = UsernamePasswordAuthenticationToken.authenticated(user, null, java.util.List.of());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }

        response.setHeader(HttpHeaders.VARY, BFF_KEY_HEADER + ", " + USER_EMAIL_HEADER);
        filterChain.doFilter(request, response);
    }

    private boolean isTrusted(String suppliedKey) {
        String expectedKey = securityProperties.bffSharedSecret();
        if (expectedKey.isBlank() || suppliedKey == null) {
            return false;
        }

        return MessageDigest.isEqual(
                expectedKey.getBytes(StandardCharsets.UTF_8), suppliedKey.getBytes(StandardCharsets.UTF_8));
    }
}
