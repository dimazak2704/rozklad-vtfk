package ua.vtc.rozklad.importer.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.List;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import ua.vtc.rozklad.importer.config.TokenRoles;

/** MockMvc JWTs with the flat {@code roles} claim, converted like in production. */
public final class TestTokens {

    private TestTokens() {
    }

    public static JwtRequestPostProcessor admin() {
        return role("ADMIN", "admin");
    }

    public static JwtRequestPostProcessor service() {
        return role("SERVICE", "service-account-rozklad-notification");
    }

    public static JwtRequestPostProcessor teacher() {
        return role("TEACHER", "krentsin");
    }

    private static JwtRequestPostProcessor role(String role, String username) {
        return jwt().jwt(token -> token.claim("roles", List.of(role))
                        .claim("preferred_username", username))
                .authorities(TokenRoles::authorities);
    }
}
