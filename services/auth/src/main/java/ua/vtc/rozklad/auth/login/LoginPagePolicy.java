package ua.vtc.rozklad.auth.login;

import java.util.Collection;

/**
 * Content-Security-Policy of the sign-in and error pages (DESIGN §5.4): only same-origin styles, fonts,
 * images and scripts, no framing; fonts also from {@code data:}, because Vite inlines small font subsets. {@code form-action} also lists the origins of the registered redirect
 * URIs, because Chrome applies it to the redirect chain after the login POST
 * ({@code /auth/login → /auth/oauth2/authorize → <SPA>/login/callback}).
 */
public final class LoginPagePolicy {

    private LoginPagePolicy() {
    }

    public static String contentSecurityPolicy(Collection<String> redirectOrigins) {
        String formAction = redirectOrigins.isEmpty() ? "'self'" : "'self' " + String.join(" ", redirectOrigins);
        return "default-src 'none'; style-src 'self'; img-src 'self' data:; font-src 'self' data:; script-src 'self'; "
                + "form-action " + formAction + "; frame-ancestors 'none'; base-uri 'none'";
    }
}
