package ua.vtc.rozklad.core.shared.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** Maps Keycloak realm roles ({@code realm_access.roles}) to {@code ROLE_<NAME>} authorities. */
public class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    static final String REALM_ACCESS = "realm_access";
    static final String ROLES = "roles";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        return realmRoles(jwt).stream()
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }

    /** Realm roles of the token in their original spelling; empty when the claim is missing. */
    public static Set<String> realmRoles(Jwt jwt) {
        if (!(jwt.getClaims().get(REALM_ACCESS) instanceof Map<?, ?> realmAccess)) {
            return Set.of();
        }
        if (!(realmAccess.get(ROLES) instanceof Collection<?> roles)) {
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
