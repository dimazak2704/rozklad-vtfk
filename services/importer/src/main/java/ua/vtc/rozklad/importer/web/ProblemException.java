package ua.vtc.rozklad.importer.web;

import org.springframework.http.HttpStatus;

/**
 * An error answered as RFC 9457 ProblemDetail with a fixed type slug and Ukrainian title/detail
 * (409 job state, 413 file size, 415 file type, 503 dependency down).
 */
public class ProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String type;
    private final String title;

    public ProblemException(HttpStatus status, String type, String title, String detail) {
        super(detail);
        this.status = status;
        this.type = type;
        this.title = title;
    }

    public static ProblemException conflict(String detail) {
        return new ProblemException(HttpStatus.CONFLICT, "conflict", "Конфлікт стану", detail);
    }

    public static ProblemException unsupportedFile(String detail) {
        return new ProblemException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-file", "Непідтримуваний файл", detail);
    }

    public static ProblemException tooLarge(String detail) {
        return new ProblemException(HttpStatus.CONTENT_TOO_LARGE, "payload-too-large", "Файл завеликий", detail);
    }

    public static ProblemException unavailable(String detail) {
        return new ProblemException(HttpStatus.SERVICE_UNAVAILABLE, "service-unavailable", "Сервіс тимчасово недоступний",
                detail);
    }

    public HttpStatus status() {
        return status;
    }

    public String type() {
        return type;
    }

    public String title() {
        return title;
    }
}
