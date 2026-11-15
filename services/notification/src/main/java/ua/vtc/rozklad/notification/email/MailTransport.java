package ua.vtc.rozklad.notification.email;

/**
 * Port of the e-mail channel: {@link SmtpMailTransport} over Spring's {@code JavaMailSender},
 * {@link DisabledMailTransport} while the channel is off, a fake in tests.
 */
public interface MailTransport {

    /**
     * Sends the letter.
     *
     * @return the {@code Message-ID} the letter went out with
     * @throws MailTransportException when the server refused it or could not be reached
     */
    String send(OutgoingMail mail);
}
