package ua.vtc.rozklad.core.shared.error;

/** 404 Not Found with a Ukrainian message, e.g. {@code NotFoundException.of("Групу", id)}. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

    /** @param subject accusative form, e.g. «Групу», «Викладача», «Розпорядження» */
    public static NotFoundException of(String subject, Object id) {
        return new NotFoundException("%s з ідентифікатором %s не знайдено".formatted(subject, id));
    }
}
