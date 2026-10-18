package dev.vcore.shared.config;

import dev.vcore.identity.security.BffAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfiguration {
    @Bean
    org.springframework.session.jdbc.PostgreSqlJdbcIndexedSessionRepositoryCustomizer postgresSessionAttributes() {
        return new org.springframework.session.jdbc.PostgreSqlJdbcIndexedSessionRepositoryCustomizer();
    }

    @Bean
    CookieSerializer sessionCookie(@Value("${auth.secure-cookie:true}") boolean secure) {
        var cookie = new DefaultCookieSerializer();
        cookie.setCookieName("VCORE_SESSION");
        cookie.setUseHttpOnlyCookie(true);
        cookie.setUseSecureCookie(secure);
        cookie.setSameSite("Lax");
        cookie.setCookiePath("/");
        return cookie;
    }

    @Bean
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            BffAuthenticationFilter bffAuthenticationFilter,
            org.springframework.jdbc.core.simple.JdbcClient jdbc)
            throws Exception {
        return http.csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .requireCsrfProtectionMatcher(request ->
                                !java.util.Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())
                                        && request.getRequestURI().startsWith("/api/")))
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, exception) -> {
                            var authentication =
                                    org.springframework.security.core.context.SecurityContextHolder.getContext()
                                            .getAuthentication();
                            response.setStatus(
                                    authentication == null
                                                    || authentication
                                                            instanceof
                                                            org.springframework.security.authentication
                                                                    .AnonymousAuthenticationToken
                                            ? 401
                                            : 403);
                        })
                        .authenticationEntryPoint((request, response, exception) -> response.setStatus(401)))
                .authorizeHttpRequests(authorize -> authorize
                        // Container redispatch only: initial REQUEST authorization and stream policy remain enforced.
                        .dispatcherTypeMatchers(
                                jakarta.servlet.DispatcherType.ASYNC, jakarta.servlet.DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers(
                                "/api/auth/**",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/v3/api-docs/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**")
                        .permitAll()
                        .requestMatchers(request -> request.getRequestURI().startsWith("/api/")
                                && !java.util.Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod()))
                        .hasRole("OPERATOR")
                        .anyRequest()
                        .hasAnyRole("DEMO_VIEWER", "OPERATOR", "VIEWER"))
                .httpBasic(basic -> basic.disable())
                .formLogin(login -> login.loginProcessingUrl("/api/auth/login")
                        .successHandler((request, response, authentication) -> response.setStatus(204))
                        .failureHandler((request, response, exception) -> response.setStatus(401)))
                .logout(logout -> logout.logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
                .addFilterBefore(bffAuthenticationFilter, org.springframework.security.web.csrf.CsrfFilter.class)
                .addFilterBefore(new LoginThrottleFilter(jdbc), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(
                        new SessionAccountRefreshFilter(jdbc),
                        org.springframework.security.web.context.SecurityContextHolderFilter.class)
                .build();
    }
}
