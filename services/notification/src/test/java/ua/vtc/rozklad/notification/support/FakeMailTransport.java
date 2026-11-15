package ua.vtc.rozklad.notification.support;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import ua.vtc.rozklad.notification.email.MailTransport;
import ua.vtc.rozklad.notification.email.MailTransportException;
import ua.vtc.rozklad.notification.email.OutgoingMail;

/** SMTP stand-in of the integration tests: records the letters, can fail the next sends on purpose. */
public class FakeMailTransport implements MailTransport {

    private final List<OutgoingMail> sent = new CopyOnWriteArrayList<>();
    private final Deque<MailTransportException> failures = new ArrayDeque<>();

    public synchronized void reset() {
        sent.clear();
        failures.clear();
    }

    /** The next sends fail with these, in order; later ones succeed. */
    public synchronized void failNext(MailTransportException... next) {
        failures.addAll(List.of(next));
    }

    @Override
    public synchronized String send(OutgoingMail mail) {
        MailTransportException failure = failures.poll();
        if (failure != null) {
            throw failure;
        }
        sent.add(mail);
        return mail.messageId();
    }

    public List<OutgoingMail> sent() {
        return List.copyOf(sent);
    }

    public List<OutgoingMail> sentTo(String address) {
        return sent.stream().filter(mail -> mail.to().equalsIgnoreCase(address)).toList();
    }
}
