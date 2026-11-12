package ua.vtc.rozklad.notification.config;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Roles of an access token: the flat {@code roles} claim of the sign-in service {@code auth}. Spring
 * authorities are {@code ROLE_<NAME>}.
 */
public final class TokenRoles {

    static final String ROLES = "roles";

    private TokenRoles() {
    }

    /** Roles in their original spelling; empty when the claim is missing or malformed. */
    public static Set<String> of(Jwt jwt) {
        return names(jwt.getClaims().get(ROLES));
    }

    /** {@code ROLE_<NAME>} authorities; a method reference fits {@code JwtAuthenticationConverter}. */
    public static Collection<GrantedAuthority> authorities(Jwt jwt) {
        return of(jwt).stream()
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }

    private static Set<String> names(Object claim) {
        if (!(claim instanceof Collection<?> roles)) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object role : roles) {
            if (role instanceof String name && !name.isBlank()) {
                result.add(name);
            }
        }
        return Set.copyOf(result);
    }
}
