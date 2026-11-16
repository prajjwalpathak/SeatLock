package testsupport;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full application on a real Tomcat port (not MockMvc): requests travel through sockets, Tomcat's
 * worker pool and the whole filter chain. Used by the concurrency races. One shared annotation keeps
 * this to a single extra cached context (with its own containers) for all classes that use it.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Tomcat's default TCP backlog is 100; Windows answers a burst of 200 connects beyond it with a reset.
        // Production sits behind Nginx (few long-lived upstream connections), so this only matters for the races.
        properties = "server.tomcat.accept-count=512")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public @interface RealServerTest {
}
