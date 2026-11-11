package ua.vtc.rozklad.auth.config;

import java.time.Clock;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Shared infrastructure beans. */
@Configuration(proxyBeanMethods = false)
public class CommonBeans {

    /** BCrypt cost 12 (ASVS 2.4.4 asks for at least 10); stored as {@code {bcrypt}…} so the id can change later. */
    public static final int BCRYPT_STRENGTH = 12;

    @Bean
    PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(BCRYPT_STRENGTH)));
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
