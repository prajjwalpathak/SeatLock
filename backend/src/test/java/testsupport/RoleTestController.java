package testsupport;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoints, one per role, to check @PreAuthorize + the role hierarchy. */
@RestController
@RequestMapping("/test/roles")
public class RoleTestController {

    @GetMapping("/user")
    @PreAuthorize("hasRole('USER')")
    String user() {
        return "ok";
    }

    @GetMapping("/organizer")
    @PreAuthorize("hasRole('ORGANIZER')")
    String organizer() {
        return "ok";
    }

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    String admin() {
        return "ok";
    }
}
