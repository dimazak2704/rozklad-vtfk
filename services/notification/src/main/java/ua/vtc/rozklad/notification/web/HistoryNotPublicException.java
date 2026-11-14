package ua.vtc.rozklad.notification.web;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 404 for a visitor who asks for an order older than core's public history window. The ProblemDetail matches
 * core's: type {@code urn:rozklad:problem:history-not-public}, {@code code} {@value #CODE} and
 * {@code publicHistoryFrom}, the first date a visitor may open.
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
