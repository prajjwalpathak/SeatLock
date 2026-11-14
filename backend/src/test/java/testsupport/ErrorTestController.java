package testsupport;

import com.seatlock.common.exception.ConflictException;
import com.seatlock.common.exception.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints that trigger each error path of GlobalExceptionHandler.
 * Lives outside com.seatlock so the application's component scan never registers it.
 */
@RestController
@RequestMapping("/test/errors")
public class ErrorTestController {

    public record SampleRequest(@NotBlank String name, @Min(1) @Max(10) int quantity) {}

    @GetMapping("/not-found")
    void notFound() {
        throw new NotFoundException("Event", 42);
    }

    @GetMapping("/conflict")
    void conflict() {
        throw new ConflictException("SEAT_ALREADY_HELD", "Seat A1 is already held");
    }

    @GetMapping("/boom")
    void boom() {
        throw new IllegalStateException("db-password=hunter2"); // must never reach the client
    }

    @GetMapping("/forbidden")
    void forbidden() {
        throw new AccessDeniedException("internal rule ADMIN_ONLY"); // like a failed @PreAuthorize
    }

    @GetMapping("/bad-credentials")
    void badCredentials() {
        throw new BadCredentialsException("user alice not found"); // must never reach the client
    }

    @PostMapping("/validate")
    SampleRequest validate(@Valid @RequestBody SampleRequest body) {
        return body;
    }
}