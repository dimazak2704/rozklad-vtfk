package ua.vtc.rozklad.notification.web;

/** 400 for a rule between fields that Bean Validation cannot express; rendered as {@code errors[]}. */
public class FieldValidationException extends RuntimeException {

    private final String field;

    public FieldValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
