package ua.vtc.rozklad.core.substitution.internal.change;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

/** Kind-specific required/forbidden fields of {@link CreateChangeRequest}, reported per field. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidChangeRequest.Validator.class)
public @interface ValidChangeRequest {

    String message() default "Некоректні дані зміни";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidChangeRequest, CreateChangeRequest> {

        @Override
        public boolean isValid(CreateChangeRequest request, ConstraintValidatorContext context) {
            if (request == null) {
                return true;
            }
            List<ChangeKindRules.Violation> violations = ChangeKindRules.check(request);
            if (violations.isEmpty()) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            violations.forEach(violation -> context.buildConstraintViolationWithTemplate(escape(violation.message()))
                    .addPropertyNode(violation.field())
                    .addConstraintViolation());
            return false;
        }

        /** Messages are literal text, not templates. */
        private static String escape(String message) {
            return message.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}").replace("$", "\\$");
        }
    }
}
