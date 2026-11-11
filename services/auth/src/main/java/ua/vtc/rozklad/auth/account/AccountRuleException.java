package ua.vtc.rozklad.auth.account;

/**
 * A refused account change. {@link Rule} carries the HTTP status and the stable problem type of the internal
 * API (DESIGN §1.3); {@code reason} refines {@code password-rejected} (a {@link PasswordPolicy.Reason} code).
 */
public class AccountRuleException extends RuntimeException {

    private final Rule rule;
    private final String reason;

    public AccountRuleException(Rule rule, String message) {
        this(rule, message, null);
    }

    public AccountRuleException(Rule rule, String message, String reason) {
        super(message);
        this.rule = rule;
        this.reason = reason;
    }

    public static AccountRuleException passwordRejected(PasswordPolicy.Reason reason) {
        return new AccountRuleException(Rule.PASSWORD_REJECTED, "Password rejected by the policy", reason.code());
    }

    public Rule rule() {
        return rule;
    }

    public String reason() {
        return reason;
    }

    public enum Rule {
        NOT_FOUND(404, "not-found"),
        VALIDATION(400, "validation"),
        PASSWORD_REJECTED(400, "password-rejected"),
        USERNAME_TAKEN(409, "username-taken"),
        EMAIL_TAKEN(409, "email-taken"),
        LAST_ADMIN(409, "last-admin"),
        ACCOUNT_REVOKED(409, "account-revoked");

        private final int status;
        private final String type;

        Rule(int status, String type) {
            this.status = status;
            this.type = type;
        }

        public int status() {
            return status;
        }

        public String type() {
            return type;
        }
    }
}
