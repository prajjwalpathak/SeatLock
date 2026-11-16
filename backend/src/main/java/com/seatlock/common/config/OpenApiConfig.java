package com.seatlock.common.config;

import com.seatlock.common.idempotency.Idempotent;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import org.springdoc.core.customizers.OperationCustomizer;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Metadata shown at the top of Swagger UI and in /v3/api-docs. */
@Configuration
public class OpenApiConfig {

    /** Name to reference with @SecurityRequirement(name = BEARER_AUTH) on protected endpoints. */
    public static final String BEARER_AUTH = "bearerAuth";

    /** Documents the required Idempotency-Key header on every {@link Idempotent} endpoint. */
    @Bean
    OperationCustomizer idempotencyKeyHeader() {
        return (operation, handlerMethod) -> {
            if (handlerMethod.hasMethodAnnotation(Idempotent.class)) {
                operation.addParametersItem(new HeaderParameter()
                        .name(Idempotent.HEADER)
                        .required(true)
                        .description("New random value (e.g. a UUID) per attempt; reuse it only to retry the same request")
                        .schema(new StringSchema().minLength(8).maxLength(64).pattern("^[A-Za-z0-9_-]+$")));
            }
            return operation;
        };
    }

    @Bean
    OpenAPI seatLockOpenApi() {
        // Adds the "Authorize" button in Swagger UI: paste the accessToken from /auth/login
        Components components = new Components().addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"));
        return new OpenAPI().components(components).info(new Info()
                .title("SeatLock API")
                .version("v1")
                .description("Flash-sale ticket booking where nobody ever gets the same seat twice. "
                        + "Money is in paise (long); all times are UTC (ISO-8601). "
                        + "Errors are RFC 7807 problem+json with `code` and `requestId`.\n\n"
                        + "**Try it:** `POST /api/v1/auth/login` with `demo.user@seatlock.test` or "
                        + "`organizer.cinema@seatlock.test` (password `seatlock-demo`), copy `accessToken`, "
                        + "click **Authorize** and paste it. Tokens last 8 hours."));
    }
}