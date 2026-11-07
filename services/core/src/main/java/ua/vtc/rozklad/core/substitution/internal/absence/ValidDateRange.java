package ua.vtc.rozklad.core.substitution.internal.absence;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * {@code dateTo} is not before {@code dateFrom} and the period is at most a year; the error is
 * reported on {@code dateTo} so the form highlights it.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidDateRange.Validator.class)
public @interface ValidDateRange {

    long MAX_DAYS = 366;

    String message() default "Дата завершення не може бути раніше за дату початку";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Request with a date period. */
    interface Period {

        LocalDate dateFrom();

        LocalDate dateTo();
    }

    class Validator implements ConstraintValidator<ValidDateRange, Period> {

        @Override
        public boolean isValid(Period period, ConstraintValidatorContext context) {
            if (period == null || period.dateFrom() == null || period.dateTo() == null) {
                return true;
            }
            String problem = null;
            if (period.dateTo().isBefore(period.dateFrom())) {
                problem = "Дата завершення не може бути раніше за дату початку";
            } else if (ChronoUnit.DAYS.between(period.dateFrom(), period.dateTo()) >= MAX_DAYS) {
                problem = "Відсутність не може тривати понад рік";
            }
            if (problem == null) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(problem).addPropertyNode("dateTo").addConstraintViolation();
            return false;
        }
    }
}
