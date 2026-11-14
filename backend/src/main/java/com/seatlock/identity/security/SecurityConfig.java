package com.seatlock.identity.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Stateless API security: no sessions, no cookies, no CSRF (tokens travel in the Authorization
 * header, which browsers never attach automatically). Public reads are listed explicitly;
 * everything else needs an authenticated user (deny by default). Callers authenticate with our
 * own RS256 access token: {@code Authorization: Bearer <jwt>}.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    /** Anonymous POST endpoints (bodies validated in the controllers). */
    static final String[] PUBLIC_POST = {
            "/api/v1/auth/register",
            "/api/v1/auth/login"
    };

    /** Anonymous read-only endpoints. {@code /**} also matches the bare path. */
    static final String[] PUBLIC_GET = {
            "/api/v1/events/**",
            "/api/v1/shows/**",
            "/api/v1/system/**"
    };

    static final String[] PUBLIC_ANY = {
            "/actuator/health/**",
            "/actuator/info",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/error"                 // container error page must stay reachable
    };

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) throws Exception {

        ProblemDetailSecurityHandler problemHandler = new ProblemDetailSecurityHandler(exceptionResolver);

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource)) // before auth: preflights pass
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST).permitAll()
                        .requestMatchers(PUBLIC_ANY).permitAll()
                        // Coarse role gates by URL prefix; @PreAuthorize on each controller is the main check
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/organizer/**").hasRole("ORGANIZER")
                        .anyRequest().authenticated())
                // "Authorization: Bearer <jwt>" is verified with the JwtDecoder bean (JwtConfig)
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(problemHandler)   // bad/expired token → 401
                        .accessDeniedHandler(problemHandler))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(problemHandler)   // no token → 401
                        .accessDeniedHandler(problemHandler));      // 403
        return http.build();
    }

    /** JWT "roles": ["ORGANIZER"] → authority ROLE_ORGANIZER; principal name = "sub" (user id). */
    static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName(TokenService.CLAIM_ROLES);
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        converter.setPrincipalClaimName("sub");
        return converter;
    }
}
