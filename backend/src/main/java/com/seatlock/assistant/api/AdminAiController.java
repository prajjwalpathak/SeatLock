package com.seatlock.assistant.api;

import com.seatlock.assistant.ai.AiFeature;
import com.seatlock.assistant.ai.AiGateway;
import com.seatlock.assistant.ai.AiResult;
import com.seatlock.common.config.OpenApiConfig;
import com.seatlock.common.ratelimit.RateLimited;
import com.seatlock.identity.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin checks for the AI guardrails: is a model configured, what state is the breaker in, does a call work. */
@Tag(name = "Admin", description = "Admin-only endpoints")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/v1/admin/ai")
public class AdminAiController {

    private static final String PING_SYSTEM_PROMPT =
            "You are a connectivity check for the SeatLock ticketing app. Answer in one short sentence.";

    private final AiGateway gateway;

    public AdminAiController(AiGateway gateway) {
        this.gateway = gateway;
    }

    @Operation(summary = "AI configuration and circuit-breaker state (ADMIN)")
    @GetMapping("/status")
    public AiStatusResponse status() {
        return new AiStatusResponse(gateway.enabled(), gateway.model(), gateway.circuitState().name());
    }

    @Operation(summary = "Send one message through the AI gateway (ADMIN); returns the answer or the fallback reason")
    @RateLimited("ai")
    @PostMapping("/ping")
    public AiPingResponse ping(@CurrentUserId long userId, @Valid @RequestBody AiPingRequest request) {
        AiResult result = gateway.complete(AiFeature.PING, userId, PING_SYSTEM_PROMPT, request.message());
        return switch (result) {
            case AiResult.Answer a -> new AiPingResponse(a.outcome(), a.text(), a.model(), a.latencyMs());
            case AiResult.Fallback f -> new AiPingResponse(f.outcome(), null, null, null);
        };
    }
}
