package ua.vtc.rozklad.notification.email;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.notification.config.MailChannelProperties;
import ua.vtc.rozklad.notification.config.StandClock;
import ua.vtc.rozklad.notification.core.CoreDtos.MailRecipient;
import ua.vtc.rozklad.notification.core.UpstreamRejectedException;
import ua.vtc.rozklad.notification.core.UpstreamUnavailableException;
import ua.vtc.rozklad.notification.email.EmailMessage.Recipient;
import ua.vtc.rozklad.notification.email.EmailRetryPolicy.Decision;
import ua.vtc.rozklad.notification.email.MailModels.MailModel;

/**
 * Step one of the worker: turns due intents into letters. It asks core who the people are (cached), keeps those
 * with a verified address who did not switch the kind off, renders one letter per person and stores it. core
 * unreachable ⇒ the intent is retried with backoff; Telegram is not affected.
 *
 * <p>Letters about one change to one person settle: a new letter replaces the earlier ones still waiting
 * (status SUPERSEDED), a change while the first letter still waits gives one letter of the first kind with the
 * final details, and a cancellation is sent only after a letter of that change reached the person — a notice
 * cancelled within the settle delay sends nothing. What reached him counts only since the last cancellation he
 * got: a change that went to another teacher and came back starts afresh. The person's settings are checked
 * against the kind the letter is finally written as (a change folded into a waiting notice is a notice).
 */
@Component
public class EmailExpander {

    private static final Logger log = LoggerFactory.getLogger(EmailExpander.class);
    private static final Set<EmailKind> CHANGE_KINDS = EnumSet.of(EmailKind.SUBSTITUTION_NOTICE,
            EmailKind.SUBSTITUTION_REQUEST, EmailKind.SUBSTITUTION_CHANGED, EmailKind.SUBSTITUTION_CANCELLED,
            EmailKind.SUBSTITUTION_REMINDER);
    private static final Set<EmailMessageStatus> LIVE = EnumSet.of(EmailMessageStatus.QUEUED,
            EmailMessageStatus.RETRYING, EmailMessageStatus.SENT);
    private static final String OPTED_OUT = "вимкнув такі листи";

    private final EmailNoticeRepository notices;
    private final EmailMessageRepository messages;
    private final EmailRecipients recipients;
    private final EmailPreferences preferences;
    private final MailTexts texts;
    private final MailTemplates templates;
    private final EmailRetryPolicy retryPolicy;
    private final MailChannelProperties properties;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final Clock clock;
    private final MeterRegistry meters;

    public EmailExpander(EmailNoticeRepository notices, EmailMessageRepository messages, EmailRecipients recipients,
                         EmailPreferences preferences, MailTexts texts, MailTemplates templates,
                         EmailRetryPolicy retryPolicy, MailChannelProperties properties,
                         TransactionTemplate transactions, JsonMapper json, Clock clock, MeterRegistry meters) {
        this.notices = notices;
        this.messages = messages;
        this.recipients = recipients;
        this.preferences = preferences;
        this.texts = texts;
        this.templates = templates;
        this.retryPolicy = retryPolicy;
        this.properties = properties;
        this.transactions = transactions;
        this.json = json;
        this.clock = StandClock.real(clock);
        this.meters = meters;
    }

    /** Claims due intents (lease) and expands them one by one. @return number of claimed intents */
    public int expandDue() {
        Instant now = clock.instant();
        List<EmailNotice> batch = Objects.requireNonNullElse(transactions.execute(tx -> {
            List<EmailNotice> due = notices.lockDue(now, properties.batchSize());
            due.forEach(notice -> notice.lease(now.plus(properties.lease()), now));
            return due;
        }), List.of());
        for (EmailNotice notice : batch) {
            expand(notice);
        }
        return batch.size();
    }

    /** Resolves the people outside any transaction (an HTTP call to core), then records the letters. */
    void expand(EmailNotice notice) {
        MailModel model;
        try {
            model = json.readValue(notice.getModel(), notice.getKind().model());
        } catch (JacksonException e) {
            log.error("E-mail intent {} ({}) has an unreadable model: {}", notice.getId(), notice.getKind(),
                    e.getOriginalMessage());
            finish(notice.getId(), n -> n.markFailed("Дані листа не вдалося прочитати.", clock.instant()));
            count("failed");
            return;
        }
        List<MailRecipient> people;
        try {
            people = recipients.of(notice);
        } catch (UpstreamUnavailableException | UpstreamRejectedException e) {
            Decision decision = retryPolicy.onTransientFailure(notice.getAttempts() + 1, clock.instant());
            String error = "Не вдалося дізнатися адресатів у core: " + e.getMessage();
            log.warn("E-mail intent {} ({}): recipients unavailable: {}", notice.getId(), notice.getKind(),
                    e.getMessage());
            switch (decision) {
                case Decision.Retry retry -> finish(notice.getId(), n -> n.markRetry(error, retry.at(), clock.instant()));
                case Decision.Fail ignored -> finish(notice.getId(), n -> n.markFailed(error, clock.instant()));
            }
            count("recipients_unavailable");
            return;
        }
        Selection selection = select(notice, people);
        try {
            transactions.executeWithoutResult(tx -> record(notice.getId(), model, selection));
        } catch (RuntimeException e) {
            if (e instanceof DataAccessException || e instanceof TransactionException) {
                throw e;
            }
            // шаблон чи текст листа: повтор не допоможе
            log.error("E-mail intent {} ({}) could not be rendered", notice.getId(), notice.getKind(), e);
            finish(notice.getId(), n -> n.markFailed("Лист не вдалося сформувати: " + e.getMessage(),
                    clock.instant()));
            count("failed");
        }
    }

    /**
     * Who gets a letter; the others are counted and logged by account id (never by address). The settings of a
     * letter that settles are checked later, against the kind it is written as.
     */
    private Selection select(EmailNotice notice, List<MailRecipient> people) {
        EmailKind kind = notice.getKind();
        List<Recipient> targets = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        if (people.isEmpty()) {
            skipped.add(notice.getTarget() == EmailNoticeTarget.TEACHER
                    ? "викладач не має облікового запису" : "немає адресатів");
            log.info("E-mail {} ({}): nobody to write to ({})", notice.getId(), kind, notice.getTarget());
            count("no_address");
            return new Selection(targets, skipped, Map.of());
        }
        Map<UUID, EmailPreference> settings = preferences.of(people.stream().map(MailRecipient::accountId).toList());
        for (MailRecipient person : people) {
            MailAddresses.Verification address = MailAddresses.verify(person.email(), person.googleLinked(),
                    properties.trustedDomains());
            String reason = null;
            String outcome = null;
            if (!settles(notice) && !settings.get(person.accountId()).allows(kind)) {
                reason = OPTED_OUT;
                outcome = "opted_out";
            } else if (address.status() == MailAddresses.Status.MISSING) {
                reason = "немає e-mail";
                outcome = "no_address";
            } else if (!address.verified()) {
                reason = "адресу не підтверджено";
                outcome = "unverified";
            }
            if (reason != null) {
                log.info("E-mail {} ({}) skipped for account {}: {}", notice.getId(), kind, person.accountId(),
                        outcome);
                skipped.add(person.accountId() + ": " + reason);
                count(outcome);
                continue;
            }
            targets.add(new Recipient(person.accountId(), person.displayName(), address.address()));
        }
        return new Selection(targets, skipped, settings);
    }

    private static boolean settles(EmailNotice notice) {
        return notice.getKind().followsChange() && notice.getCorrelationId() != null;
    }

    private void record(UUID noticeId, MailModel model, Selection selection) {
        Instant now = clock.instant();
        EmailNotice notice = notices.findById(noticeId)
                .orElseThrow(() -> new IllegalStateException("E-mail intent disappeared: " + noticeId));
        int written = 0;
        List<String> notes = new ArrayList<>(selection.skipped());
        for (Recipient target : selection.targets()) {
            if (messages.existsByDedupKey(noticeId + ":" + target.accountId())) {
                continue;
            }
            Optional<EmailKind> kind;
            if (settles(notice)) {
                Settled settled = settle(notice, target, now);
                kind = settled.kind();
                if (kind.isPresent() && !selection.settings().get(target.accountId()).allows(kind.get())) {
                    // switched off: the letters still waiting stay as they are, as if this one never came
                    log.info("E-mail {} ({} as {}) skipped for account {}: opted_out", noticeId, notice.getKind(),
                            kind.get(), target.accountId());
                    notes.add(target.accountId() + ": " + OPTED_OUT);
                    count("opted_out");
                    continue;
                }
                settled.waiting().forEach(message -> {
                    message.markSuperseded(now);
                    count("superseded");
                });
            } else {
                kind = Optional.of(notice.getKind());
            }
            if (kind.isEmpty()) {
                notes.add(target.accountId() + ": попереднього листа про цю заміну не було");
                count("not_needed");
                continue;
            }
            MailTemplates.Rendered letter = templates.render(texts.compose(kind.get(), model));
            Instant notBefore = kind.get().followsChange() ? now.plus(properties.settleDelay()) : now;
            messages.save(new EmailMessage(notice, kind.get(), target, letter, notBefore, now));
            written++;
        }
        notice.markExpanded(notes.isEmpty() ? null : String.join("; ", notes), now);
        if (written > 0) {
            log.debug("E-mail intent {} ({}): {} letter(s) queued", noticeId, notice.getKind(), written);
        }
    }

    /**
     * Earlier letters of the same change to the same person (locked, oldest first): the kind of the new letter
     * (empty = nothing to send) and the letters still waiting, which the new one replaces.
     */
    private Settled settle(EmailNotice notice, Recipient target, Instant now) {
        List<EmailMessage> earlier = messages.lockEarlier(notice.getCorrelationId(), target.accountId(),
                CHANGE_KINDS, LIVE);
        List<EmailMessage> waiting = earlier.stream()
                .filter(message -> message.getStatus().isPending() && !message.isLeased(now))
                .toList();
        // лише те, що дійшло після останнього скасування: заміна пішла до іншого й повернулась — рахунок наново
        int lastCancellation = -1;
        for (int i = 0; i < earlier.size(); i++) {
            if (earlier.get(i).getKind() == EmailKind.SUBSTITUTION_CANCELLED && reached(earlier.get(i), now)) {
                lastCancellation = i;
            }
        }
        boolean reached = earlier.subList(lastCancellation + 1, earlier.size()).stream()
                .anyMatch(message -> reached(message, now));
        Optional<EmailKind> kind = switch (notice.getKind()) {
            case SUBSTITUTION_CANCELLED -> reached ? Optional.of(EmailKind.SUBSTITUTION_CANCELLED) : Optional.empty();
            case SUBSTITUTION_CHANGED -> reached ? Optional.of(EmailKind.SUBSTITUTION_CHANGED)
                    : waiting.isEmpty() ? Optional.empty() : Optional.of(waiting.getLast().getKind());
            default -> Optional.of(notice.getKind());
        };
        return new Settled(kind, waiting);
    }

    /** Sent, or being sent right now by another worker: it reached the person (or is about to). */
    private static boolean reached(EmailMessage message, Instant now) {
        return message.getStatus() == EmailMessageStatus.SENT || message.isLeased(now);
    }

    private void finish(UUID noticeId, Consumer<EmailNotice> change) {
        transactions.executeWithoutResult(tx -> notices.findById(noticeId).ifPresent(change));
    }

    private void count(String outcome) {
        meters.counter("notification.emails", "outcome", outcome).increment();
    }

    private record Selection(List<Recipient> targets, List<String> skipped, Map<UUID, EmailPreference> settings) {
    }

    private record Settled(Optional<EmailKind> kind, List<EmailMessage> waiting) {
    }
}
