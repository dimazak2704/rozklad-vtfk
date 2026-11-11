package ua.vtc.rozklad.auth.config;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Actuator lives on its own management port (never proxied or published) and exposes only {@code health};
 * the compose healthcheck and the deploy gate read it without credentials.
 */
@Configuration(proxyBeanMethods = false)
public class ActuatorSecurityConfig {

    @Bean
    @Order(0)
    SecurityFilterChain actuatorChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
