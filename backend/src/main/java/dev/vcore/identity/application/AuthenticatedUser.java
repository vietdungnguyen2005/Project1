package dev.vcore.identity.application;

import java.util.UUID;

public record AuthenticatedUser(UUID id, String email, String displayName) {}
