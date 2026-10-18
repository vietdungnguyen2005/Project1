package dev.vcore.shared.config;

import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class OperatorAccounts {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    UserDetailsService operatorUsers(JdbcClient jdbc) {
        return username -> jdbc.sql(
                        "SELECT username, password_hash, role, enabled FROM auth_account WHERE username=:username")
                .param("username", username.strip().toLowerCase(Locale.ROOT))
                .query((rs, row) -> User.withUsername(rs.getString("username"))
                        .password(rs.getString("password_hash"))
                        .roles(rs.getString("role"))
                        .disabled(!rs.getBoolean("enabled"))
                        .build())
                .optional()
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }

    @Bean
    ApplicationRunner provisionOperator(
            JdbcClient jdbc,
            @Value("${auth.bootstrap.username:}") String username,
            @Value("${auth.bootstrap.password-hash:}") String passwordHash,
            @Value("${auth.secure-cookie:true}") boolean secureCookie,
            @Value("${DEPLOYMENT_ENVIRONMENT:local}") String environment) {
        return arguments -> {
            if ("production".equalsIgnoreCase(environment) && !secureCookie) {
                throw new IllegalStateException("Production session cookies must be Secure");
            }
            if (username.isBlank() && passwordHash.isBlank()) return;
            if (username.isBlank()
                    || username.length() > 100
                    || !passwordHash.matches("\\$2[aby]\\$[0-9]{2}\\$.{53}")) {
                throw new IllegalStateException("Operator bootstrap requires a username and a BCrypt password hash");
            }
            String normalized = username.strip().toLowerCase(Locale.ROOT);
            if (normalized.equals("viewer@v-core.local")
                    || jdbc.sql("SELECT COUNT(*) FROM app_user WHERE LOWER(email)=:email")
                                    .param("email", normalized)
                                    .query(Long.class)
                                    .single()
                            != 1) {
                throw new IllegalStateException("Operator must be an existing non-demo workspace user");
            }
            jdbc.sql(
                            "INSERT INTO auth_account(username,password_hash,role,user_id) SELECT :username,:hash,'OPERATOR',id FROM app_user WHERE LOWER(email)=:username ON CONFLICT(username) DO NOTHING")
                    .param("username", username.strip().toLowerCase(Locale.ROOT))
                    .param("hash", passwordHash)
                    .update();
        };
    }
}
