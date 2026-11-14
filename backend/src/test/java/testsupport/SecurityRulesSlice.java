package testsupport;

import com.seatlock.common.config.CorsConfig;
import com.seatlock.identity.security.MethodSecurityConfig;
import com.seatlock.identity.security.SecurityConfig;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * For @WebMvcTest slices: the app's real security rules, @PreAuthorize + role hierarchy, CORS, with a mock JwtDecoder
 * (no keys needed). Tests that need real tokens use @IntegrationTest instead.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import({SecurityConfig.class, MethodSecurityConfig.class, CorsConfig.class})
@MockitoBean(types = JwtDecoder.class)
public @interface SecurityRulesSlice {
}
