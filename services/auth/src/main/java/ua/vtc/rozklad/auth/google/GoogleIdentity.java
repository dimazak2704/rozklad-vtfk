package ua.vtc.rozklad.auth.google;

import java.util.Locale;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;

/**
 * What the ID token of a Google sign-in says about the person. Only the ID token counts: Spring has checked its
 * signature, issuer, audience, expiry and nonce.
 *
 * @param subject       Google's stable account id ({@code sub}), never reused
 * @param email         the address in lower case, or {@code null}
 * @param emailVerified Google's {@code email_verified}
 * @param hostedDomain  the Workspace domain ({@code hd}); absent for a consumer Google account
 */
public record GoogleIdentity(String subject, String email, boolean emailVerified, String hostedDomain) {

    static GoogleIdentity of(OidcIdToken token) {
        Object verified = token.getClaim("email_verified");
        String email = token.getEmail();
        String hd = token.getClaimAsString("hd");
        return new GoogleIdentity(token.getSubject(),
                email == null ? null : email.strip().toLowerCase(Locale.ROOT),
                Boolean.TRUE.equals(verified) || "true".equals(verified),
                hd == null || hd.isBlank() ? null : hd.strip().toLowerCase(Locale.ROOT));
    }

    /** The part after {@code @}, or {@code null}. */
    String emailDomain() {
        if (email == null) {
            return null;
        }
        int at = email.lastIndexOf('@');
        return at < 0 ? null : email.substring(at + 1);
    }
}
