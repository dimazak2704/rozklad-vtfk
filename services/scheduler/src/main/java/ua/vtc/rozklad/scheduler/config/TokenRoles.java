package ua.vtc.rozklad.scheduler.config;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Roles of an access token: the flat {@code roles} claim of our sign-in service, or Keycloak's
 * {@code realm_access.roles} while a token still comes from Keycloak. Spring authorities are
 * {@code ROLE_<NAME>}.
 */
public final class TokenRoles {

    static final String ROLES = "roles";
    static final String REALM_ACCESS = "realm_access";

    private TokenRoles() {
    }

    /** Roles in their original spelling; empty when neither claim is present. */
    public static Set<String> of(Jwt jwt) {
        Object flat = jwt.getClaims().get(ROLES);
        if (flat != null) {
            return names(flat);
        }
        if (jwt.getClaims().get(REALM_ACCESS) instanceof Map<?, ?> realmAccess) {
            return names(realmAccess.get(ROLES));
        }
        return Set.of();
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
