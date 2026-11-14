package ua.vtc.rozklad.core.shared.error;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 404 for an anonymous visitor who asks for substitutions or a schedule older than the public window
 * ({@code ua.vtc.rozklad.core.settings.PublicHistory}). The ProblemDetail has the type
 * {@link ProblemTypes#HISTORY_NOT_PUBLIC}, {@code code} {@value #CODE} and {@code publicHistoryFrom}, the
 * first date the visitor may open, so a client can offer it instead of an error.
 */
public class HistoryNotPublicException extends RuntimeException {

    public static final String CODE = "HISTORY_NOT_PUBLIC";

    private final LocalDate publicHistoryFrom;

    public HistoryNotPublicException(LocalDate publicHistoryFrom, String message) {
        super(message);
        this.publicHistoryFrom = Objects.requireNonNull(publicHistoryFrom, "publicHistoryFrom");
    }

    public LocalDate getPublicHistoryFrom() {
        return publicHistoryFrom;
    }
}
