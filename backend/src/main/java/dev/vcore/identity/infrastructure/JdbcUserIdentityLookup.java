package dev.vcore.identity.infrastructure;

import dev.vcore.identity.application.AuthenticatedUser;
import dev.vcore.identity.application.UserIdentityLookup;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcUserIdentityLookup implements UserIdentityLookup {

    private final JdbcClient jdbcClient;

    JdbcUserIdentityLookup(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<AuthenticatedUser> findByEmail(String email) {
        String normalizedEmail = email.strip().toLowerCase(Locale.ROOT);
        return jdbcClient
                .sql("""
                        SELECT id, email, display_name
                        FROM app_user
                        WHERE LOWER(email) = :email
                        """)
                .param("email", normalizedEmail)
                .query((resultSet, rowNumber) -> new AuthenticatedUser(
                        resultSet.getObject("id", java.util.UUID.class),
                        resultSet.getString("email"),
                        resultSet.getString("display_name")))
                .optional();
    }
}
