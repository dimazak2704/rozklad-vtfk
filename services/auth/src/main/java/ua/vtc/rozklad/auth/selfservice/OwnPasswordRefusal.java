package ua.vtc.rozklad.auth.selfservice;

/**
 * A refused change of one's own password. {@link Problem} carries the HTTP status, the stable problem code
 * ({@code urn:rozklad:auth:<code>}) and the Ukrainian text the SPA shows; {@code reason} refines
 * {@code password-rejected} (a {@code PasswordPolicy.Reason} code or {@code same-as-current}).
 */
public class OwnPasswordRefusal extends RuntimeException {

    private final Problem problem;
    private final String reason;

    OwnPasswordRefusal(Problem problem) {
        this(problem, problem.detail(), null);
    }

    OwnPasswordRefusal(Problem problem, String detail, String reason) {
        super(detail);
        this.problem = problem;
        this.reason = reason;
    }

    public Problem problem() {
        return problem;
    }

    public String reason() {
        return reason;
    }

    public enum Problem {
        VALIDATION(400, "validation", "Вкажіть поточний і новий пароль."),
        PASSWORD_REJECTED(400, "password-rejected", "Новий пароль не підходить."),
        ACCOUNT_INACTIVE(403, "account-inactive", "Обліковий запис вимкнено."),
        DEMO_ACCOUNT(403, "demo-account", "Пароль спільного демонстраційного доступу змінити не можна."),
        PASSWORD_NOT_SET(409, "password-not-set",
                "У цього облікового запису немає пароля: вхід лише через Google. Попросіть у навчальній частині "
                        + "посилання для пароля."),
        CURRENT_PASSWORD_WRONG(422, "current-password-wrong", "Поточний пароль неправильний."),
        TOO_MANY_ATTEMPTS(429, "too-many-attempts", "Забагато невдалих спроб. Спробуйте пізніше.");

        private final int status;
        private final String code;
        private final String detail;

        Problem(int status, String code, String detail) {
            this.status = status;
            this.code = code;
            this.detail = detail;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }

        public String detail() {
            return detail;
        }
    }
}
