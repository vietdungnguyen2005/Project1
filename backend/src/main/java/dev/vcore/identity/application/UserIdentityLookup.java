package dev.vcore.identity.application;

import java.util.Optional;

public interface UserIdentityLookup {

    Optional<AuthenticatedUser> findByEmail(String email);
}
