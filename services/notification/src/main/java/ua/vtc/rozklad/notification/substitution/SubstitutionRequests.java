package ua.vtc.rozklad.notification.substitution;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.ConfirmationDecision;
import ua.vtc.rozklad.contracts.events.SubstitutionConfirmationRequested;
import ua.vtc.rozklad.contracts.events.SubstitutionStatusChanged;
import ua.vtc.rozklad.notification.binding.ChatBinding;
import ua.vtc.rozklad.notification.binding.ChatBindings;
import ua.vtc.rozklad.notification.config.BotProperties;
import ua.vtc.rozklad.notification.core.CoreClient;
import ua.vtc.rozklad.notification.core.CoreDtos.ChangeSummary;
import ua.vtc.rozklad.notification.core.CoreDtos.PublicTeacher;
import ua.vtc.rozklad.notification.core.CoreQueries;
import ua.vtc.rozklad.notification.core.UpstreamRejectedException;
import ua.vtc.rozklad.notification.core.UpstreamUnavailableException;
import ua.vtc.rozklad.notification.delivery.DeliveryPurpose;
import ua.vtc.rozklad.notification.delivery.OutboundDraft;
import ua.vtc.rozklad.notification.delivery.OutboundMessageSent;
import ua.vtc.rozklad.notification.delivery.Outbox;
import ua.vtc.rozklad.notification.delivery.Recipient;
import ua.vtc.rozklad.notification.text.WebLinks;

/**
 * Substitution confirmations in Telegram: sends the request to the teacher's chat, takes his
 * answer (core {@code POST /api/internal/changes/{id}/reply}), edits the messages to the final
 * state, reminds after the deadline and keeps admin chats informed.
 *
 * <p>The local copy of a request is written by the teacher's button and by core's events at the
 * same time (core publishes {@code substitution.status-changed} for the very answer the button sent).
 * Every writer reads the row with a lock and only moves a pending request to a final state, so the
 * two converge to the same state and neither of them fails.
 */
@Service
public class SubstitutionRequests {

    private static final Logger log = LoggerFactory.getLogger(SubstitutionRequests.class);
    private static final String UNKNOWN_TEACHER = "Викладач";
    private static final int MAX_RECORD_ATTEMPTS = 3;

    private final SubstitutionRequestRepository requests;
    private final SubstitutionMessageRepository messages;
    private final ChatBindings bindings;
    private final Outbox outbox;
    private final CoreClient core;
    private final CoreQueries queries;
    private final WebLinks links;
    private final TransactionTemplate transactions;
    private final BotProperties properties;
    private final Clock clock;

    public SubstitutionRequests(SubstitutionRequestRepository requests, SubstitutionMessageRepository messages,
                                ChatBindings bindings, Outbox outbox, CoreClient core, CoreQueries queries,
                                WebLinks links, TransactionTemplate transactions, BotProperties properties,
                                Clock clock) {
        this.requests = requests;
        this.messages = messages;
        this.bindings = bindings;
        this.outbox = outbox;
        this.core = core;
        this.queries = queries;
        this.links = links;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * {@code substitution.confirmation-requested}. A repeated request for a pending change is a reminder.
     * A teacher without Telegram is not reported right away: {@link #reportTeachersWithoutTelegram()}
     * tells admins once per teacher and date about requests that are still unanswered.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onConfirmationRequested(SubstitutionConfirmationRequested event) {
        Instant now = clock.instant();
        Optional<SubstitutionRequest> existing = requests.findForUpdate(event.changeId());
        boolean sameTeacher = existing.map(found -> found.getTeacherId().equals(event.teacherId())).orElse(false);
        boolean reminder = sameTeacher && existing.get().isPending()
                && !messages.findByChangeId(event.changeId()).isEmpty();
        if (existing.isPresent() && !sameTeacher) {
            retireMessages(existing.get(), event.eventId());
        }
        SubstitutionRequest request = existing.map(found -> {
            found.refresh(event, now);
            return found;
        }).orElseGet(() -> requests.save(SubstitutionRequest.from(event, now)));

        Optional<ChatBinding> chat = bindings.teacherChat(event.teacherId());
        if (chat.isPresent()) {
            request.markSent(now);
            outbox.enqueue(OutboundDraft.text(Recipient.of(chat.get()),
                            reminder ? DeliveryPurpose.SUBSTITUTION_REMINDER : DeliveryPurpose.SUBSTITUTION_REQUEST,
                            SubstitutionTexts.request(request, reminder))
                    .withKeyboard(SubstitutionTexts.buttons(event.changeId()))
                    .correlatedWith(event.changeId())
                    .forEvent(event.eventId(), "request"));
            return;
        }
        request.markNoTelegram(now);
    }

    /** {@code substitution.status-changed}: final states edit the request messages; a refusal goes to admins. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onStatusChanged(SubstitutionStatusChanged event) {
        Optional<SubstitutionRequest> request = requests.findForUpdate(event.changeId());
        request.ifPresent(found -> {
            boolean changed = found.changeStatus(event.status(), clock.instant());
            String finalText = SubstitutionTexts.finalState(found, event.status());
            if (changed && finalText != null) {
                editMessages(found, finalText, null, null, event.eventId());
            }
        });
        if (event.status() == ChangeStatus.DECLINED) {
            String name = teacherName(event.teacherId());
            String text = request
                    .map(found -> SubstitutionTexts.adminDeclined(name, found, links.adminChanges(found.getLessonDate())))
                    .orElseGet(() -> SubstitutionTexts.adminDeclinedUnknown(name, event.date(), event.groupCode(),
                            event.date() == null ? "" : links.adminChanges(event.date())));
            notifyAdmins(text, event.eventId(), "declined");
        }
    }

    /** Remembers ids of sent request messages so they can be edited later. */
    @EventListener
    public void onSent(OutboundMessageSent sent) {
        if (!sent.purpose().isSubstitutionRequest() || sent.correlationId() == null
                || sent.telegramMessageId() == null) {
            return;
        }
        if (requests.existsById(sent.correlationId())
                && !messages.existsByChatIdAndMessageId(sent.chatId(), sent.telegramMessageId())) {
            messages.save(new SubstitutionMessage(sent.correlationId(), sent.chatId(), sent.telegramMessageId(),
                    clock.instant()));
        }
    }

    /**
     * The teacher pressed ✅/❌. core decides (it checks the teacher and the status); the local copy is
     * updated afterwards and the other messages of the change are edited through the queue. Once core
     * has accepted the answer the outcome is {@link ReplyOutcome.Done} even if the local bookkeeping
     * loses a race or fails: the teacher's answer is recorded where it matters.
     */
    public ReplyOutcome reply(UUID changeId, long chatId, Long clickedMessageId, ConfirmationDecision decision) {
        Optional<ChatBinding> teacher = bindings.teacherBinding(chatId);
        if (teacher.isEmpty()) {
            return new ReplyOutcome.NotTeacher();
        }
        UUID teacherId = teacher.get().getTeacherId();
        Optional<SubstitutionRequest> known = requests.findById(changeId);
        if (known.isPresent() && !known.get().getTeacherId().equals(teacherId)) {
            return new ReplyOutcome.WrongTeacher();
        }
        ChangeStatus wanted = decision == ConfirmationDecision.CONFIRM ? ChangeStatus.CONFIRMED : ChangeStatus.DECLINED;
        ChangeSummary result;
        try {
            result = core.replyToChange(changeId, teacherId, decision, null);
        } catch (UpstreamRejectedException e) {
            if (e.isConflict()) {
                return alreadyAnswered(changeId, wanted, known);
            }
            if (e.isForbidden()) {
                return new ReplyOutcome.WrongTeacher();
            }
            if (e.isNotFound()) {
                return new ReplyOutcome.NotFound(known.orElse(null));
            }
            return new ReplyOutcome.Rejected(e.detail());
        } catch (UpstreamUnavailableException e) {
            log.warn("Cannot send the reply for change {}: {}", changeId, e.getMessage());
            return new ReplyOutcome.Unavailable();
        }
        ChangeStatus status = result != null && result.status() != null ? result.status() : wanted;
        SubstitutionRequest updated = recordReplySafely(changeId, status, chatId, clickedMessageId);
        log.info("Teacher answered {} for change {} → {}", decision, changeId, status);
        return new ReplyOutcome.Done(updated != null ? updated : known.orElse(null), status);
    }

    /**
     * core says the change no longer waits for an answer. If it already holds exactly this answer (a
     * double tap, or the answer given from another copy of the request), the teacher gets the same
     * result again instead of «неактуальний».
     */
    private ReplyOutcome alreadyAnswered(UUID changeId, ChangeStatus wanted, Optional<SubstitutionRequest> known) {
        Optional<SubstitutionRequest> fresh = requests.findById(changeId).or(() -> known);
        if (fresh.isPresent() && fresh.get().getStatus() == wanted) {
            return new ReplyOutcome.Done(fresh.get(), wanted);
        }
        return new ReplyOutcome.NotPending(fresh.orElse(null));
    }

    private SubstitutionRequest recordReplySafely(UUID changeId, ChangeStatus status, long chatId,
                                                  Long clickedMessageId) {
        for (int attempt = 1; attempt <= MAX_RECORD_ATTEMPTS; attempt++) {
            try {
                return transactions.execute(tx -> recordReply(changeId, status, chatId, clickedMessageId));
            } catch (ConcurrencyFailureException e) {
                log.debug("Recording the answer for change {} collided (attempt {}): {}", changeId, attempt,
                        e.getMessage());
            } catch (DataAccessException | TransactionException e) {
                log.warn("core accepted the answer for change {}, but the local copy was not updated: {}", changeId,
                        e.getMessage());
                return null;
            }
        }
        log.warn("core accepted the answer for change {}, but the local copy kept colliding; the status event "
                + "will update it", changeId);
        return null;
    }

    private SubstitutionRequest recordReply(UUID changeId, ChangeStatus status, long chatId, Long clickedMessageId) {
        Optional<SubstitutionRequest> request = requests.findForUpdate(changeId);
        request.ifPresent(found -> {
            // лише очікуваний запит переходить у фінальний стан: подія core могла встигнути першою
            // (той самий статус) або вже пізніша (APPLIED) — тоді нічого не перезаписуємо
            if (found.isPending() && found.changeStatus(status, clock.instant())) {
                String text = SubstitutionTexts.finalState(found, status);
                if (text != null) {
                    editMessages(found, text, chatId, clickedMessageId, null);
                }
            }
        });
        return request.orElse(null);
    }

    /**
     * Deadline passed without an answer: one reminder to the teacher and a notice to admins. Requests
     * that never reached the teacher (no Telegram) were already reported by
     * {@link #reportTeachersWithoutTelegram()}; past lessons are not worth a notice.
     */
    @Transactional
    public int remindOverdue() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock);
        List<SubstitutionRequest> overdue = requests.findOverdue(now);
        for (SubstitutionRequest request : overdue) {
            request.markReminded(now);
            if (request.isWithoutTelegram() || request.getLessonDate().isBefore(today)) {
                continue;
            }
            String key = request.getChangeId() + ":" + request.getDeadline().toEpochMilli();
            bindings.teacherChat(request.getTeacherId()).ifPresent(chat -> outbox.enqueue(
                    OutboundDraft.text(Recipient.of(chat), DeliveryPurpose.SUBSTITUTION_REMINDER,
                                    SubstitutionTexts.request(request, true))
                            .withKeyboard(SubstitutionTexts.buttons(request.getChangeId()))
                            .correlatedWith(request.getChangeId())
                            .dedupBy("reminder:" + key)));
            String text = SubstitutionTexts.adminOverdue(teacherName(request.getTeacherId()), request,
                    links.adminChanges(request.getLessonDate()));
            for (ChatBinding admin : bindings.adminChats()) {
                outbox.enqueue(OutboundDraft.text(Recipient.of(admin), DeliveryPurpose.SUBSTITUTION_ADMIN, text)
                        .correlatedWith(request.getChangeId())
                        .dedupBy("overdue:" + key + ":" + admin.getChatId()));
            }
        }
        if (!overdue.isEmpty()) {
            log.info("{} substitution request(s) passed the deadline without an answer", overdue.size());
        }
        return overdue.size();
    }

    /**
     * Calm warnings about teachers without Telegram: only requests still pending after
     * {@code app.bot.no-telegram-notice-delay} (a seeded or quickly confirmed/published order causes
     * none), only for today and later, and at most one message per teacher and lesson date for each
     * admin chat — the same teacher's further changes of that date are in the web panel.
     *
     * @return number of (teacher, date) warnings queued
     */
    @Transactional
    public int reportTeachersWithoutTelegram() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock);
        List<SubstitutionRequest> due = requests.findUnreportedWithoutTelegram(
                now.minus(properties.noTelegramNoticeDelay()));
        Map<TeacherDate, List<SubstitutionRequest>> byTeacherAndDate = new LinkedHashMap<>();
        for (SubstitutionRequest request : due) {
            request.markAdminWarned(now);
            if (!request.getLessonDate().isBefore(today)) {
                byTeacherAndDate.computeIfAbsent(new TeacherDate(request.getTeacherId(), request.getLessonDate()),
                        key -> new ArrayList<>()).add(request);
            }
        }
        if (byTeacherAndDate.isEmpty()) {
            return 0;
        }
        List<ChatBinding> admins = bindings.adminChats();
        int warnings = 0;
        for (Map.Entry<TeacherDate, List<SubstitutionRequest>> entry : byTeacherAndDate.entrySet()) {
            TeacherDate key = entry.getKey();
            String text = SubstitutionTexts.adminNoTelegram(teacherName(key.teacherId()), entry.getValue(),
                    links.adminChanges(key.date()));
            boolean queued = false;
            for (ChatBinding admin : admins) {
                queued |= !outbox.enqueue(OutboundDraft.text(Recipient.of(admin), DeliveryPurpose.SUBSTITUTION_ADMIN,
                                text)
                        .dedupBy("no-telegram:" + key.teacherId() + ":" + key.date() + ":" + admin.getChatId()))
                        .isEmpty();
            }
            if (queued) {
                warnings++;
            }
        }
        log.info("Teachers without Telegram: {} warning(s) for {} pending request(s)", warnings, due.size());
        return warnings;
    }

    @Transactional(readOnly = true)
    public Optional<SubstitutionRequest> find(UUID changeId) {
        return requests.findById(changeId);
    }

    @Transactional
    public int purgeLessonsBefore(LocalDate before) {
        return requests.deleteLessonsBefore(before);
    }

    /** The change went to another teacher: the previous one's messages lose their buttons. */
    private void retireMessages(SubstitutionRequest request, UUID eventId) {
        List<SubstitutionMessage> sent = messages.findByChangeId(request.getChangeId());
        for (SubstitutionMessage message : sent) {
            bindings.teacherBinding(message.getChatId()).ifPresent(chat -> outbox.enqueue(
                    OutboundDraft.edit(Recipient.of(chat), DeliveryPurpose.SUBSTITUTION_STATUS, message.getMessageId(),
                                    SubstitutionTexts.outdated(request), null)
                            .correlatedWith(request.getChangeId())
                            .forEvent(eventId, "retire-" + message.getMessageId())));
        }
        messages.deleteAll(sent);
    }

    private void editMessages(SubstitutionRequest request, String text, Long skipChatId, Long skipMessageId,
                              UUID eventId) {
        for (SubstitutionMessage message : messages.findByChangeId(request.getChangeId())) {
            if (skipChatId != null && skipChatId == message.getChatId() && skipMessageId != null
                    && skipMessageId == message.getMessageId()) {
                continue;
            }
            Optional<ChatBinding> chat = bindings.teacherBinding(message.getChatId());
            if (chat.isEmpty()) {
                continue;
            }
            OutboundDraft draft = OutboundDraft.edit(Recipient.of(chat.get()), DeliveryPurpose.SUBSTITUTION_STATUS,
                    message.getMessageId(), text, null).correlatedWith(request.getChangeId());
            outbox.enqueue(eventId != null
                    ? draft.forEvent(eventId, "edit-" + message.getMessageId())
                    : draft.dedupBy("reply:" + request.getChangeId() + ":" + message.getMessageId() + ":"
                            + request.getStatus()));
        }
    }

    private void notifyAdmins(String text, UUID eventId, String part) {
        for (ChatBinding admin : bindings.adminChats()) {
            outbox.enqueue(OutboundDraft.text(Recipient.of(admin), DeliveryPurpose.SUBSTITUTION_ADMIN, text)
                    .forEvent(eventId, part));
        }
    }

    /** Display name for admin texts; core down ⇒ the name saved with the teacher's binding. */
    private String teacherName(UUID teacherId) {
        if (teacherId == null) {
            return UNKNOWN_TEACHER;
        }
        try {
            Optional<PublicTeacher> teacher = queries.teacher(teacherId);
            if (teacher.isPresent() && !teacher.get().label().isBlank()) {
                return teacher.get().label();
            }
        } catch (UpstreamUnavailableException | UpstreamRejectedException e) {
            log.debug("Teacher name from core unavailable: {}", e.getMessage());
        }
        return bindings.teacherChat(teacherId).map(ChatBinding::getTitle).orElse(UNKNOWN_TEACHER);
    }

    private record TeacherDate(UUID teacherId, LocalDate date) {
    }

    /** Result of a teacher's answer, for the bot's reply. */
    public sealed interface ReplyOutcome {

        /** {@code request} is null when the change was never seen by notification. */
        record Done(SubstitutionRequest request, ChangeStatus status) implements ReplyOutcome {
        }

        record NotTeacher() implements ReplyOutcome {
        }

        record WrongTeacher() implements ReplyOutcome {
        }

        record NotPending(SubstitutionRequest request) implements ReplyOutcome {
        }

        record NotFound(SubstitutionRequest request) implements ReplyOutcome {
        }

        record Unavailable() implements ReplyOutcome {
        }

        record Rejected(String detail) implements ReplyOutcome {
        }
    }
}
