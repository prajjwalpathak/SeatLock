package com.seatlock.identity.oauth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/**
 * A second, separate filter chain for the Google sign-in redirects only:
 * {@code GET /oauth2/authorization/google} (start) and {@code /login/oauth2/code/google} (callback).
 * Runs before the API chain (Order 1) and never touches /api/**.
 */
@Configuration(proxyBeanMethods = false)
public class OAuth2LoginConfig {

    static final String[] OAUTH2_PATHS = {"/oauth2/**", "/login/oauth2/**"};

    @Bean
    @Order(1)
    SecurityFilterChain oauth2LoginFilterChain(HttpSecurity http,
                                               ClientRegistrationRepository clients,
                                               StringRedisTemplate redis,
                                               JsonMapper jsonMapper,
                                               GoogleLoginHandlers handlers) throws Exception {
        // PKCE (S256) even though we're a confidential client: recommended by OAuth 2.1
        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
                clients, OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

        http
                .securityMatcher(OAUTH2_PATHS)
                .csrf(AbstractHttpConfigurer::disable)   // the callback is protected by state + cookie instead
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .oauth2Login(oauth -> oauth
                        .authorizationEndpoint(ep -> ep
                                .authorizationRequestResolver(resolver)
                                .authorizationRequestRepository(
                                        new RedisOAuth2AuthorizationRequestRepository(redis, jsonMapper)))
                        .authorizedClientRepository(new DiscardingAuthorizedClientRepository())
                        .successHandler(handlers)    // → {frontend}/auth/callback?code=<one-time code>
                        .failureHandler(handlers));  // → {frontend}/login?error=<CODE>
        return http.build();
    }
}
