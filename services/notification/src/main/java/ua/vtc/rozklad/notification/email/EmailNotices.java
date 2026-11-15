package ua.vtc.rozklad.notification.email;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.notification.config.StandClock;
import ua.vtc.rozklad.notification.email.MailModels.MailModel;

/**
 * The only API other packages use to e-mail somebody. It writes an intent ({@code email_notice}) in the caller's
 * transaction — for events the one that marks the event processed, so a redelivered or replayed event queues
 * nothing twice — and calls nobody: the worker resolves the people and their addresses later. With the channel
 * off nothing is written.
 */
@Service
public class EmailNotices {

    private static final Logger log = LoggerFactory.getLogger(EmailNotices.class);

    private final EmailNoticeRepository repository;
    private final EmailPreferences preferences;
    private final MailChannel channel;
    private final JsonMapper json;
    private final Clock clock;

    public EmailNotices(EmailNoticeRepository repository, EmailPreferences preferences, MailChannel channel,
                        JsonMapper json, Clock clock) {
        this.repository = repository;
        this.preferences = preferences;
        this.channel = channel;
        this.json = json;
        this.clock = StandClock.real(clock); // real time, like the Telegram queue
    }

    /** {@code {eventId}:mail:{part}}: one intent per event and part. */
    public static String eventKey(UUID eventId, String part) {
        return eventId + ":mail:" + part;
    }

    /**
     * A letter to the account linked to {@code teacherId}. {@code lessonDate}/{@code lessonEndsAt}: a substitution
     * letter is dropped once the lesson is over.
     */
    @Transactional
    public Optional<UUID> toTeacher(EmailKind kind, UUID teacherId, MailModel model, UUID correlationId,
                                    LocalDate lessonDate, LocalTime lessonEndsAt, UUID eventId, String dedupKey) {
        if (teacherId == null) {
            return Optional.empty();
        }
        return write(kind, EmailNoticeTarget.TEACHER, teacherId, null, model, correlationId, lessonDate,
                lessonEndsAt, eventId, dedupKey);
    }

    /** A letter to every dispatcher (enabled ADMIN accounts) who keeps {@code kind} on. */
    @Transactional
    public Optional<UUID> toAdmins(EmailKind kind, MailModel model, UUID correlationId, UUID eventId,
                                   String dedupKey) {
        return write(kind, EmailNoticeTarget.ADMINS, null, null, model, correlationId, null, null, eventId,
                dedupKey);
    }

    /** A letter to one account (the test letter). */
    @Transactional
    public Optional<UUID> toAccount(EmailKind kind, UUID accountId, MailModel model, String dedupKey) {
        return write(kind, EmailNoticeTarget.ACCOUNT, null, accountId, model, null, null, null, null, dedupKey);
    }

    public boolean enabled() {
        return channel.enabled();
    }

    private Optional<UUID> write(EmailKind kind, EmailNoticeTarget target, UUID teacherId, UUID accountId,
                                 MailModel model, UUID correlationId, LocalDate lessonDate, LocalTime lessonEndsAt,
                                 UUID eventId, String dedupKey) {
        if (!channel.enabled()) {
            return Optional.empty();
        }
        if (!kind.model().isInstance(model)) {
            throw new IllegalArgumentException(kind + " needs " + kind.model().getSimpleName());
        }
        if (repository.existsByDedupKey(dedupKey)) {
            log.debug("Skipping duplicate e-mail intent {}", dedupKey);
            return Optional.empty();
        }
        if (!preferences.mightWant(kind)) {
            log.debug("E-mail {} skipped: nobody switched it on", kind);
            return Optional.empty();
        }
        EmailNotice notice = repository.save(new EmailNotice(kind, target, teacherId, accountId, correlationId,
                lessonDate, lessonEndsAt, json.writeValueAsString(model), eventId, dedupKey, clock.instant()));
        log.debug("Queued e-mail intent {} ({}) for {}", notice.getId(), kind, target);
        return Optional.of(notice.getId());
    }
}
