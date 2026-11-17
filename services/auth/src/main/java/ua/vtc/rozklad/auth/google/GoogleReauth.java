package ua.vtc.rozklad.auth.google;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Google re-authentication for setting a first password (CONTRACTS §0.1). The SPA sends the browser to
 * {@code /auth/oauth2/authorization/google?reauth_return=<address in the app>}: the authorization request then asks
 * Google to show the account chooser and to re-authenticate ({@code prompt=select_account}, {@code max_age=0}; Google
 * documents only the first, the second is a request it may ignore) and carries the return address as an attribute
 * of the stored request. After the callback the handlers read that attribute from the very request the code
 * exchange used ({@link Requests}), so a stale or foreign {@code reauth_return} never redirects another sign-in.
 * <p>
 * The return address must be on one of the SPA origins ({@code app.auth.web-origins}); anything else is ignored and
 * the sign-in continues as a plain one.
 */
public final class GoogleReauth {

    /** The query parameter of the authorization endpoint. */
    public static final String RETURN_PARAMETER = "reauth_return";
    /** The attribute of the stored authorization request. */
    static final String RETURN_ATTRIBUTE = "rozklad.reauth_return";
    /** Request attribute: the authorization request the callback removed from the session. */
    static final String REMOVED_REQUEST = GoogleReauth.class.getName() + ".removed";
    static final int MAX_RETURN_LENGTH = 2048;

    private GoogleReauth() {
    }

    /** The return address of a re-authentication, if this callback finishes one. */
    static Optional<String> returnOf(HttpServletRequest request) {
        if (request.getAttribute(REMOVED_REQUEST) instanceof OAuth2AuthorizationRequest removed
                && removed.getAttribute(RETURN_ATTRIBUTE) instanceof String target) {
            return Optional.of(target);
        }
        return Optional.empty();
    }

    /**
     * {@code value} if it is an absolute http(s) address without user info on one of {@code origins}
     * ({@code scheme://host[:port]}), else empty. Only printable ASCII without a backslash passes.
     */
    static Optional<String> validReturn(String value, Set<String> origins) {
        if (value == null || value.isEmpty() || value.length() > MAX_RETURN_LENGTH
                || value.chars().anyMatch(c -> c < 0x21 || c == '\\' || c > 0x7e)) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!uri.isAbsolute() || uri.isOpaque() || uri.getRawUserInfo() != null || uri.getHost() == null
                    || !(scheme.equals("http") || scheme.equals("https"))) {
                return Optional.empty();
            }
            return origins.contains(origin(uri)) ? Optional.of(value) : Optional.empty();
        } catch (URISyntaxException malformed) {
            return Optional.empty();
        }
    }

    static Set<String> origins(Collection<URI> webOrigins) {
        return webOrigins.stream().map(GoogleReauth::origin).collect(Collectors.toUnmodifiableSet());
    }

    private static String origin(URI uri) {
        String value = uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT);
        return uri.getPort() > 0 ? value + ":" + uri.getPort() : value;
    }

    /** Adds the re-authentication parameters and the return address when the start carries a valid one. */
    static final class Resolver implements OAuth2AuthorizationRequestResolver {

        private final OAuth2AuthorizationRequestResolver delegate;
        private final Set<String> origins;

        Resolver(OAuth2AuthorizationRequestResolver delegate, Set<String> origins) {
            this.delegate = delegate;
            this.origins = origins;
        }

        @Override
        public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
            return reauth(request, delegate.resolve(request));
        }

        @Override
        public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
            return reauth(request, delegate.resolve(request, clientRegistrationId));
        }

        private OAuth2AuthorizationRequest reauth(HttpServletRequest request, OAuth2AuthorizationRequest plain) {
            if (plain == null) {
                return null;
            }
            Optional<String> target = validReturn(request.getParameter(RETURN_PARAMETER), origins);
            if (target.isEmpty()) {
                return plain;
            }
            String uri = UriComponentsBuilder.fromUriString(plain.getAuthorizationRequestUri())
                    .replaceQueryParam("prompt", "select_account")
                    .replaceQueryParam("max_age", "0")
                    .build(true).toUriString();
            return OAuth2AuthorizationRequest.from(plain)
                    .additionalParameters(parameters -> {
                        parameters.put("prompt", "select_account");
                        parameters.put("max_age", "0");
                    })
                    .attributes(attributes -> attributes.put(RETURN_ATTRIBUTE, target.get()))
                    .authorizationRequestUri(uri)
                    .build();
        }
    }

    /** Spring's session store, plus: the request the callback removes stays readable for the handlers. */
    static final class Requests implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

        private final AuthorizationRequestRepository<OAuth2AuthorizationRequest> delegate =
                new HttpSessionOAuth2AuthorizationRequestRepository();

        @Override
        public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
            return delegate.loadAuthorizationRequest(request);
        }

        @Override
        public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                HttpServletRequest request, HttpServletResponse response) {
            delegate.saveAuthorizationRequest(authorizationRequest, request, response);
        }

        @Override
        public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                HttpServletResponse response) {
            OAuth2AuthorizationRequest removed = delegate.removeAuthorizationRequest(request, response);
            if (removed != null) {
                request.setAttribute(REMOVED_REQUEST, removed);
            }
            return removed;
        }
    }
}
