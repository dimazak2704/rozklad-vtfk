package ua.vtc.rozklad.core.shared.error;

import java.net.URI;

/** Stable {@code type} URIs of ProblemDetail responses (RFC 9457). */
public final class ProblemTypes {

    public static final URI VALIDATION = of("validation");
    public static final URI NOT_FOUND = of("not-found");
    public static final URI CONFLICT = of("conflict");
    public static final URI SCHEDULE_CONFLICT = of("schedule-conflict");
    public static final URI STALE_DATA = of("stale-data");
    public static final URI DATA_INTEGRITY = of("data-integrity");
    public static final URI BUSINESS_RULE = of("business-rule");
    public static final URI UNAUTHORIZED = of("unauthorized");
    public static final URI FORBIDDEN = of("forbidden");
    public static final URI INTERNAL = of("internal");

    private ProblemTypes() {
    }

    private static URI of(String slug) {
        return URI.create("urn:rozklad:problem:" + slug);
    }
}
