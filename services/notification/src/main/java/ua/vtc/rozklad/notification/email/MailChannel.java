package ua.vtc.rozklad.notification.email;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.config.MailChannelProperties;
import ua.vtc.rozklad.notification.config.MailSafetyGuard;
import ua.vtc.rozklad.notification.config.MailSafetyGuard.Mode;
import ua.vtc.rozklad.notification.config.MailSafetyGuard.Verdict;
import ua.vtc.rozklad.notification.config.ServiceUrlsProperties;
import ua.vtc.rozklad.notification.config.StandClock;

/**
 * Whether the e-mail channel is on, decided once at startup ({@link MailSafetyGuard}), plus the sender
 * addresses and the last SMTP problem for {@code GET /api/notify/email/status}. {@code MAIL_FROM} and
 * {@code MAIL_REPLY_TO} are checked here: a configured channel with an unusable sender stops the start.
 */
@Component
public class MailChannel {

    private static final Logger log = LoggerFactory.getLogger(MailChannel.class);
    /** A failure older than this, or followed by a success, is no longer reported as the channel's problem. */
    private static final Duration PROBLEM_WINDOW = Duration.ofMinutes(15);

    private final MailChannelProperties properties;
    private final Verdict verdict;
    private final InternetAddress from;
    private final InternetAddress replyTo;
    private final String messageIdDomain;
    private final Clock clock;
    private final AtomicReference<Problem> lastProblem = new AtomicReference<>();

    public MailChannel(MailChannelProperties properties, ServiceUrlsProperties urls, Clock clock) {
        this.properties = properties;
        this.clock = StandClock.real(clock);
        this.verdict = MailSafetyGuard.check(properties, urls.publicWebUrl());
        if (verdict.enabled()) {
            this.from = sender(properties.from(), "MAIL_FROM", true);
            this.replyTo = sender(properties.replyTo(), "MAIL_REPLY_TO", false);
            this.messageIdDomain = properties.messageIdDomain() != null && !properties.messageIdDomain().isBlank()
                    ? properties.messageIdDomain().strip()
                    : MailAddresses.domain(from.getAddress());
        } else {
            this.from = null;
            this.replyTo = null;
            this.messageIdDomain = "rozklad.local";
        }
        if (properties.configured() && !verdict.enabled()) {
            log.error("E-mail channel is OFF: {}", verdict.problem());
        } else if (verdict.problem() != null) {
            log.warn("E-mail channel: {}", verdict.problem());
        }
        log.info("E-mail channel: {}{}", verdict.mode(),
                verdict.enabled() ? " (" + properties.host() + ":" + properties.port() + ")" : "");
    }

    public boolean enabled() {
        return verdict.enabled();
    }

    public Mode mode() {
        return verdict.mode();
    }

    public MailChannelProperties properties() {
        return properties;
    }

    /** {@code From}; null while the channel is off. */
    public InternetAddress from() {
        return from;
    }

    /** {@code Reply-To}; null when not configured. */
    public InternetAddress replyTo() {
        return replyTo;
    }

    public String messageIdDomain() {
        return messageIdDomain;
    }

    /** Why the channel is off or not healthy (Ukrainian), or null. */
    public String problem() {
        if (verdict.problem() != null) {
            return verdict.problem();
        }
        Problem problem = lastProblem.get();
        if (problem == null || problem.at().isBefore(clock.instant().minus(PROBLEM_WINDOW))) {
            return null;
        }
        return problem.text();
    }

    void recordFailure(String text) {
        lastProblem.set(new Problem(text, clock.instant()));
    }

    void recordSuccess() {
        lastProblem.set(null);
    }

    private static InternetAddress sender(String value, String variable, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw new IllegalStateException(variable + " is required when MAIL_HOST is set, e.g. "
                        + "\"Розклад ВТФК <rozklad@vtc.vn.ua>\"");
            }
            return null;
        }
        try {
            InternetAddress[] parsed = InternetAddress.parse(value.strip(), true);
            if (parsed.length != 1 || MailAddresses.normalize(parsed[0].getAddress()).isEmpty()) {
                throw new IllegalStateException(variable + " must be exactly one address, e.g. "
                        + "\"Розклад ВТФК <rozklad@vtc.vn.ua>\"");
            }
            InternetAddress address = parsed[0];
            if (address.getPersonal() != null) {
                address.setPersonal(MailHeaders.clean(address.getPersonal(), 80), "UTF-8");
            }
            return address;
        } catch (AddressException | java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(variable + " is not a valid address: " + e.getMessage(), e);
        }
    }

    private record Problem(String text, Instant at) {
    }
}
