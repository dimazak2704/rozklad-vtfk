package ua.vtc.rozklad.notification.email;

/** The channel is off: nothing can be sent (no letters are queued then either). */
public class DisabledMailTransport implements MailTransport {

    @Override
    public String send(OutgoingMail mail) {
        throw MailTransportException.transientFailure("Надсилання листів на сервері не налаштовано.");
    }
}
