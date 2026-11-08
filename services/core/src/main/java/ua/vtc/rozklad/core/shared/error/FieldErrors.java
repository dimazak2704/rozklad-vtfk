package ua.vtc.rozklad.core.shared.error;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects field errors of one request and throws them together, so the user sees every problem
 * of a form at once:
 * <pre>{@code
 * FieldErrors errors = new FieldErrors();
 * if (!specialties.existsById(id)) errors.add("specialtyId", "Спеціальність не знайдено");
 * errors.throwIfAny();
 * }</pre>
 */
public final class FieldErrors {

    private final List<FieldErrorDto> errors = new ArrayList<>();

    public FieldErrors add(String field, String message) {
        errors.add(new FieldErrorDto(field, message));
        return this;
    }

    public boolean isEmpty() {
        return errors.isEmpty();
    }

    /** @throws FieldValidationException (400) when at least one error was added */
    public void throwIfAny() {
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
    }
}
