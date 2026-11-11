package ua.vtc.rozklad.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Sign-in service of «Розклад ВТФК»: the OpenID Connect provider for the SPA and the service clients
 * (Spring Authorization Server), accounts and the Ukrainian sign-in page. Runs under {@code /auth}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class AuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
