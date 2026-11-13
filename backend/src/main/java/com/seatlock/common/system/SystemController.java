package com.seatlock.common.system;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "System", description = "Server utilities")
@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private final Clock clock;

    public SystemController(Clock clock) {
        this.clock = clock;
    }

    /** Current server time (UTC). Used by the frontend to correct hold-countdown clock skew. */
    @GetMapping("/time")
    public ResponseEntity<ServerTimeResponse> time() {
        Instant now = clock.instant();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())   // a cached "now" is always wrong
                .body(new ServerTimeResponse(now, now.toEpochMilli()));
    }
}