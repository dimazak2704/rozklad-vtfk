package ua.vtc.rozklad.notification.email;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import ua.vtc.rozklad.notification.config.StandClock;

/**
 * Sends letters through Spring's {@link JavaMailSender}: one {@code To} per letter (nobody sees other
 * recipients), a {@code multipart/alternative} of plain text and HTML in UTF-8, our own {@code Message-ID}
 * (Spring keeps an explicitly set one), {@code References}/{@code In-Reply-To} for threading, and the headers
 * that stop vacation auto-replies (RFC 3834). Failures are classified for {@link EmailRetryPolicy}: a 5xx reply
 * about the recipient is permanent, a refused login is a configuration problem, everything else is retried.
 * The {@code MimeMessage} itself is never logged.
 */
public class SmtpMailTransport implements MailTransport {

    private static final int MAX_REPLY = 300;

    private final JavaMailSender sender;
    private final MailChannel channel;
    private final Clock clock;

    public SmtpMailTransport(JavaMailSender sender, MailChannel channel, Clock clock) {
        this.sender = sender;
        this.channel = channel;
        this.clock = StandClock.real(clock);
    }

    @Override
    public String send(OutgoingMail mail) {
        MimeMessage message;
        try {
            message = build(mail);
        } catch (MessagingException | UnsupportedEncodingException | IllegalArgumentException e) {
            throw new MailTransportException("Лист не вдалося сформувати: " + e.getMessage(), true, false, null, e);
        }
        try {
            sender.send(message);
            return mail.messageId();
        } catch (MailAuthenticationException e) {
            throw new MailTransportException("SMTP-сервер не прийняв логін або пароль (MAIL_USERNAME, MAIL_PASSWORD)"
                    + reply(e), false, true, smtpCode(e), e);
        } catch (MailParseException | MailPreparationException e) {
            throw new MailTransportException("Лист не вдалося сформувати: " + e.getMessage(), true, false, null, e);
        } catch (MailSendException e) {
            Integer code = smtpCode(e);
            boolean recipientRefused = code != null && code >= 500 && code < 600 && refusesRecipient(e);
            throw new MailTransportException((recipientRefused ? "Сервер відхилив адресата" : "SMTP не прийняв лист")
                    + reply(e), recipientRefused, false, code, e);
        } catch (MailException e) {
            throw new MailTransportException("SMTP недоступний" + reply(e), false, false, smtpCode(e), e);
        }
    }

    MimeMessage build(OutgoingMail mail) throws MessagingException, UnsupportedEncodingException {
        String to = MailAddresses.normalize(mail.to())
                .orElseThrow(() -> new IllegalArgumentException("адреса одержувача некоректна"));
        MimeMessage message = sender.createMimeMessage();
        message.setFrom(channel.from());
        if (channel.replyTo() != null) {
            message.setReplyTo(new Address[]{channel.replyTo()});
        }
        String name = MailHeaders.clean(mail.toName(), 80);
        message.setRecipient(Message.RecipientType.TO, name.isEmpty()
                ? new InternetAddress(to) : new InternetAddress(to, name, "UTF-8"));
        message.setSubject(MailHeaders.clean(mail.subject(), 250), "UTF-8");
        message.setSentDate(Date.from(clock.instant()));

        MimeMultipart alternative = new MimeMultipart("alternative");
        MimeBodyPart text = new MimeBodyPart();
        text.setText(mail.text(), "UTF-8", "plain");
        MimeBodyPart html = new MimeBodyPart();
        html.setText(mail.html(), "UTF-8", "html");
        alternative.addBodyPart(text);
        alternative.addBodyPart(html);
        message.setContent(alternative);

        message.setHeader("Message-ID", "<" + MailHeaders.clean(mail.messageId()) + ">");
        if (mail.threadId() != null) {
            String thread = "<" + MailHeaders.clean(mail.threadId()) + ">";
            message.setHeader("In-Reply-To", thread);
            message.setHeader("References", thread);
        }
        message.setHeader("Auto-Submitted", "auto-generated");
        message.setHeader("X-Auto-Response-Suppress", "All");
        if (mail.unsubscribeUrl() != null) {
            message.setHeader("List-Unsubscribe", "<" + MailHeaders.clean(mail.unsubscribeUrl(), 500) + ">");
        }
        return message;
    }

    /** {@code : 550 5.1.1 User unknown} — the server's reply, addresses masked, capped. */
    private static String reply(MailException e) {
        Throwable smtp = findSmtpReply(e);
        String text = smtp != null ? smtp.getMessage() : rootMessage(e);
        if (text == null || text.isBlank()) {
            return "";
        }
        String cleaned = MailHeaders.clean(MailAddresses.maskAll(text), MAX_REPLY);
        return ": " + cleaned;
    }

    /** The SMTP reply code of the deepest Angus SMTP exception ({@code getReturnCode()}), if any. */
    static Integer smtpCode(Throwable e) {
        Throwable smtp = findSmtpReply(e);
        if (smtp == null) {
            return null;
        }
        try {
            Method method = smtp.getClass().getMethod("getReturnCode");
            Object value = method.invoke(smtp);
            return value instanceof Integer code && code > 0 ? code : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static boolean refusesRecipient(Throwable e) {
        for (Throwable t : chain(e)) {
            if (t instanceof SendFailedException failed
                    && (failed.getInvalidAddresses() != null && failed.getInvalidAddresses().length > 0
                    || t.getClass().getSimpleName().startsWith("SMTPAddressFailed")
                    || t.getClass().getSimpleName().startsWith("SMTPSendFailed"))) {
                return true;
            }
        }
        return false;
    }

    private static Throwable findSmtpReply(Throwable e) {
        Throwable found = null;
        for (Throwable t : chain(e)) {
            if (hasReturnCode(t)) {
                found = t;
            }
        }
        return found;
    }

    private static boolean hasReturnCode(Throwable t) {
        try {
            t.getClass().getMethod("getReturnCode");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** The exception, its causes, Jakarta Mail's next exceptions and the per-message failures of Spring. */
    private static Iterable<Throwable> chain(Throwable root) {
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        Deque<Throwable> todo = new ArrayDeque<>();
        List<Throwable> out = new ArrayList<>();
        todo.add(root);
        while (!todo.isEmpty() && out.size() < 50) {
            Throwable t = todo.poll();
            if (t == null || seen.put(t, Boolean.TRUE) != null) {
                continue;
            }
            out.add(t);
            if (t.getCause() != null) {
                todo.add(t.getCause());
            }
            if (t instanceof MessagingException messaging && messaging.getNextException() != null) {
                todo.add(messaging.getNextException());
            }
            if (t instanceof MailSendException send) {
                for (Exception nested : send.getMessageExceptions()) {
                    todo.add(nested);
                }
            }
        }
        return out;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() != null ? t.getClass().getSimpleName() + " " + t.getMessage() : t.getClass().getSimpleName();
    }
}
