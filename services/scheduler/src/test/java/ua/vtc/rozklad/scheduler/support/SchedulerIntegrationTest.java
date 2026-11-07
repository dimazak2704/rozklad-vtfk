package ua.vtc.rozklad.scheduler.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * Full scheduler on real PostgreSQL and RabbitMQ (Testcontainers). Every IT uses exactly this
 * configuration, so they share one application context and one set of containers. Progress and
 * re-delivery run faster than in production to keep the tests short.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "app.scheduler.progress-interval=300ms",
        "app.scheduler.completion-resend-interval=2s",
        "app.scheduler.max-time-limit=10m"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public @interface SchedulerIntegrationTest {
}
