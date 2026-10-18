package dev.vcore.identity.application;

import java.util.UUID;

public record AuthenticatedUser(UUID id, String email, String displayName)
        implements java.security.Principal, java.io.Serializable {
    @Override
    public String getName() {
        return email.strip().toLowerCase(java.util.Locale.ROOT);
    }
}
