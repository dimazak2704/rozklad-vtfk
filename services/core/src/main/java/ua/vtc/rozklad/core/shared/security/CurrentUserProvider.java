package ua.vtc.rozklad.core.shared.security;

import java.util.Optional;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Reads the {@link CurrentUser} of the current request from the security context. */
@Component
public class CurrentUserProvider {

    public Optional<CurrentUser> find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token && token.isAuthenticated()) {
            return Optional.of(CurrentUser.from(token.getToken()));
        }
        return Optional.empty();
    }

    /** @throws AuthenticationCredentialsNotFoundException when the request is anonymous (→ 401) */
    public CurrentUser require() {
        return find().orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Користувач не автентифікований"));
    }
}
