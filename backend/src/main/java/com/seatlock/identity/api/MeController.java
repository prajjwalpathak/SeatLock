package com.seatlock.identity.api;

import com.seatlock.common.config.OpenApiConfig;
import com.seatlock.identity.application.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final UserService userService;

    public MeController(UserService userService) {
        this.userService = userService;
    }

    /** The signed-in user, read fresh from the DB (the token only carries id + role). */
    @Operation(summary = "Current user (requires a Bearer token)")
    @GetMapping
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return UserResponse.from(userService.getById(Long.parseLong(jwt.getSubject())));
    }
}
