package com.seatlock.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Metadata shown at the top of Swagger UI and in /v3/api-docs. */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI seatLockOpenApi() {
        return new OpenAPI().info(new Info()
                .title("SeatLock API")
                .version("v1")
                .description("Flash-sale ticket booking where nobody ever gets the same seat twice. "
                        + "Money is in paise (long); all times are UTC (ISO-8601). "
                        + "Errors are RFC 7807 problem+json with `code` and `requestId`."));
    }
}