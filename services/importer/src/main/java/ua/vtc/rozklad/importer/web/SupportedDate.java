package ua.vtc.rozklad.importer.web;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.LocalDate;

/**
 * A date in the years {@value #MIN_YEAR}–{@value #MAX_YEAR}, the range core accepts (round 2);
 * {@code null} is valid (combine with {@code @NotNull}).
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = SupportedDate.Validator.class)
public @interface SupportedDate {

    int MIN_YEAR = 2000;
    int MAX_YEAR = 2100;

    String message() default "Дата має бути в межах 2000–2100 років.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    final class Validator implements ConstraintValidator<SupportedDate, LocalDate> {

        @Override
        public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
            return value == null || value.getYear() >= MIN_YEAR && value.getYear() <= MAX_YEAR;
        }
    }
}
