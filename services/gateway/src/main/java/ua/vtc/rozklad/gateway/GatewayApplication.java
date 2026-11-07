package ua.vtc.rozklad.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Edge gateway (Spring Cloud Gateway Server WebFlux): routes {@code /api/**} to the services,
 * applies CORS for the SPA, rate-limits the public API in Redis and assigns {@code X-Request-Id}.
 * Access tokens are passed through; every service validates them itself.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
