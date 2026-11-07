package ua.vtc.rozklad.scheduler.solver;

import java.util.List;

/** The problem snapshot cannot be solved as sent; the message (Ukrainian) lists what is wrong. */
public class InvalidProblemException extends RuntimeException {

    private static final int SHOWN_ERRORS = 5;

    private final List<String> errors;

    public InvalidProblemException(List<String> errors) {
        super(summary(errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> getErrors() {
        return errors;
    }

    private static String summary(List<String> errors) {
        String shown = String.join(" ", errors.subList(0, Math.min(SHOWN_ERRORS, errors.size())));
        int hidden = errors.size() - SHOWN_ERRORS;
        return "Некоректні вхідні дані генерації. " + shown + (hidden > 0 ? " …і ще " + hidden + "." : "");
    }
}
