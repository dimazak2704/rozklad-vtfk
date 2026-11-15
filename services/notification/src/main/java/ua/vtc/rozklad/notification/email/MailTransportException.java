package ua.vtc.rozklad.notification.email;

/**
 * A letter was not sent. {@code permanent}: retrying cannot help (the server refused the recipient with a 5xx
 * reply, or the letter could not be built); otherwise it is tried again later. {@code configProblem}: the
 * channel itself is misconfigured (wrong password), so every letter will fail until an admin fixes it. The
 * message is Ukrainian, carries no credentials and masks every address.
 */
public class MailTransportException extends RuntimeException {

    private final boolean permanent;
    private final boolean configProblem;
    private final Integer smtpCode;

    public MailTransportException(String message, boolean permanent, boolean configProblem, Integer smtpCode,
                                  Throwable cause) {
        super(MailAddresses.maskAll(message), cause);
        this.permanent = permanent;
        this.configProblem = configProblem;
        this.smtpCode = smtpCode;
    }

    public static MailTransportException transientFailure(String message) {
        return new MailTransportException(message, false, false, null, null);
    }

    public static MailTransportException permanentFailure(String message) {
        return new MailTransportException(message, true, false, null, null);
    }

    public boolean permanent() {
        return permanent;
    }

    public boolean configProblem() {
        return configProblem;
    }

    public Integer smtpCode() {
        return smtpCode;
    }
}
