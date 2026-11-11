package ua.vtc.rozklad.auth.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The whole auth service on a random port against real PostgreSQL (Testcontainers). Every IT uses exactly
 * this configuration, so they share one context and one container. Tests talk HTTP through {@link Browser},
 * which keeps cookies like a browser and never follows redirects on its own.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "app.auth.issuer=http://localhost:8088/auth",
        "app.auth.public-web-url=http://localhost:8088",
        "app.auth.cli-enabled=true",
        "app.auth.service-clients.rozklad-notification.secret=" + AuthIntegrationTest.NOTIFICATION_SECRET,
        "app.auth.service-clients.rozklad-importer.secret=" + AuthIntegrationTest.IMPORTER_SECRET,
        "app.auth.service-clients.rozklad-core.secret=" + AuthIntegrationTest.CORE_SECRET,
        "app.auth.lockout.max-failures=3",
        "app.auth.lockout.slowdown-failures=8",
        "app.auth.lockout.slowdown-delay=600ms",
        "app.auth.lockout.network-distinct-identifiers=6",
        "app.auth.demo-seed.enabled=false",
        "app.auth.bootstrap.username=root-admin",
        "app.auth.bootstrap.password=" + AuthIntegrationTest.BOOTSTRAP_PASSWORD})
@Import({TestcontainersConfiguration.class, TestSupportConfiguration.class})
public @interface AuthIntegrationTest {

    String NOTIFICATION_SECRET = "it-notification-secret";
    String IMPORTER_SECRET = "it-importer-secret";
    String CORE_SECRET = "it-core-secret";
    /** Test-only bootstrap password (passes the policy). */
    String BOOTSTRAP_PASSWORD = "Integration-Test-Admin-2026";
}
