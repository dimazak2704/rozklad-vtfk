package ua.vtc.rozklad.notification.substitution;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import ua.vtc.rozklad.contracts.SubstitutionRequestMode;
import ua.vtc.rozklad.contracts.events.RozkladEvent;
import ua.vtc.rozklad.contracts.events.SubstitutionConfirmationRequested;
import ua.vtc.rozklad.contracts.events.SubstitutionDetailsChanged;
import ua.vtc.rozklad.contracts.events.SubstitutionStatusChanged;
import ua.vtc.rozklad.contracts.events.SubstitutionTeacherReplied;
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
import ua.vtc.rozklad.notification.delivery.OutboundKind;
import ua.vtc.rozklad.notification.delivery.OutboundMessage;
import ua.vtc.rozklad.notification.delivery.OutboundMessageSent;
import ua.vtc.rozklad.notification.delivery.OutboundRelevance;
import ua.vtc.rozklad.notification.delivery.Outbox;
import ua.vtc.rozklad.notification.delivery.Recipient;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineKeyboard;
import ua.vtc.rozklad.notification.text.WebLinks;

/**
 * Substitutions in Telegram: sends the request (CONFIRM) or the notice of an assigned change (NOTIFY) to the
 * teacher's chat, takes his answer (core {@code POST /api/internal/changes/{id}/reply}), edits the messages to
 * the state core reports, reminds after the deadline (CONFIRM only) and keeps admin chats informed: a refusal,
 * an objection to a published change, a teacher without Telegram.
 *
 * <p>The local copy of a request is written by the teacher's button and by core's events at the
 * same time (core publishes {@code substitution.status-changed} for the very answer the button sent).
 * Every writer reads the row with a lock and only moves a pending request to a final state, so the
 * two converge to the same state and neither of them fails.
 *
 * <p>A request, notice or reminder can wait in the outbound queue (rate limits, Telegram retries) while the
 * change moves on. The worker asks {@link #obsoleteReason(OutboundMessage)} right before sending, and a message
 * Telegram accepted in that window is corrected at once by {@link #onSent(OutboundMessageSent)}.
 */
@Service
public class SubstitutionRequests implements OutboundRelevance {

    private static final Logger log = LoggerFactory.getLogger(SubstitutionRequests.class);
    private static final String UNKNOWN_TEACHER = "Викладач";
    private static final int MAX_RECORD_ATTEMPTS = 3;
    static final String REASSIGNED = "Неактуальне: заміну передано іншому викладачу.";
    static final String NOT_AWAITING = "Неактуальне: заміна вже не чекає відповіді.";
    static final String NOT_IN_FORCE = "Неактуальне: заміна вже не в силі.";
    private static final Comparator<SubstitutionRequest> BY_TEACHER_DATE_PAIR =
            Comparator.comparing(SubstitutionRequest::getTeacherId)
                    .thenComparing(SubstitutionRequest::getLessonDate)
                    .thenComparingInt(SubstitutionRequest::getPair);

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
     * {@code substitution.confirmation-requested}. A repeated request for a pending change is a reminder; when
     * what the teacher sees changed (core re-sends it after the dispatcher edited the room) it is
     * «✏️ Заміну змінено» instead, a request or notice again with the same deadline. Nothing is sent about a
     * lesson that is already over: neither a notice (NOTIFY) nor a request or its reminder (CONFIRM). A teacher
     * without Telegram is not reported right away:
     * {@link #reportTeachersWithoutTelegram()} tells admins once per teacher and date about requests and notices
     * that are still unanswered.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onConfirmationRequested(SubstitutionConfirmationRequested event) {
        Instant now = clock.instant();
        boolean notice = event.effectiveMode() == SubstitutionRequestMode.NOTIFY;
        Optional<SubstitutionRequest> existing = requests.findForUpdate(event.changeId());
        boolean sameTeacher = existing.map(found -> found.getTeacherId().equals(event.teacherId())).orElse(false);
        if (sameTeacher && !notice && existing.get().isConfirmedRequest()) {
            // core не просить підтвердити вдруге: це запізніла подія (нагадування чи нова аудиторія, надіслані
            // ще до підтвердження) — лише інформуємо про нові деталі, якщо вони є
            informOfNewDetails(existing.get(), SubstitutionRequest.Details.of(event), event.eventId());
            return;
        }
        if (existing.isPresent() && existing.get().isFinal()) {
            // скасовану чи опубліковану заміну core більше не надсилає: це запізніла подія (нагадування,
            // перевідправлене після скасування) — запит не відкриваємо знову
            return;
        }
        // the teacher already holds a message of this change that still waits for him
        boolean told = sameTeacher && existing.get().getMode() == event.effectiveMode()
                && (notice ? existing.get().isNoticeInForce() : existing.get().isPending())
                && !messages.findByChangeId(event.changeId()).isEmpty();
        SubstitutionRequest.Details before = told ? existing.get().details() : null;
        boolean sameDeadlinePassed = told && event.deadline() != null && !event.deadline().isAfter(now)
                && Objects.equals(existing.get().getDeadline(), event.deadline());
        if (existing.isPresent() && !sameTeacher) {
            retireMessages(existing.get(), event.eventId());
        }
        SubstitutionRequest request = existing.map(found -> {
            found.refresh(event, now);
            return found;
        }).orElseGet(() -> requests.save(SubstitutionRequest.from(event, now)));
        boolean updated = told && SubstitutionTexts.differs(before, request.details());
        boolean reminder = told && !notice && !updated;
        // core's deadline reminder (the same, already passed deadline, nothing else changed) is the round
        // remindOverdue() reminds about too: both use one dedup key, so the teacher gets one reminder whichever
        // comes first. An update (a new room) is its own message and is never swallowed by that key.
        boolean deadlineReminder = reminder && sameDeadlinePassed;

        if (lessonOver(event.date(), event.endsAt())) {
            // пара вже минула (і сьогоднішня теж): ні повідомляти, ні просити, ні нагадувати нема про що —
            // диспетчер вносить це для обліку
            return;
        }
        Optional<ChatBinding> chat = bindings.teacherChat(event.teacherId());
        if (chat.isPresent()) {
            request.markSent(now);
            OutboundDraft draft = notice
                    ? OutboundDraft.text(Recipient.of(chat.get()), DeliveryPurpose.SUBSTITUTION_NOTICE,
                            updated ? SubstitutionTexts.update(request, before) : SubstitutionTexts.notice(request))
                    .withKeyboard(SubstitutionTexts.noticeButtons(event.changeId()))
                    : OutboundDraft.text(Recipient.of(chat.get()),
                            reminder ? DeliveryPurpose.SUBSTITUTION_REMINDER : DeliveryPurpose.SUBSTITUTION_REQUEST,
                            updated ? SubstitutionTexts.update(request, before)
                                    : SubstitutionTexts.request(request, reminder))
                    .withKeyboard(SubstitutionTexts.buttons(event.changeId()));
            draft = draft.correlatedWith(event.changeId());
            outbox.enqueue(deadlineReminder ? draft.dedupBy(reminderKey(request))
                    : draft.forEvent(event.eventId(), "request"));
            return;
        }
        request.markNoTelegram(now);
    }

    /**
     * {@code substitution.details-changed}: the dispatcher changed what the teacher reads (the room) of a request
     * already confirmed. He gets «✏️ Заміну змінено» with what changed and the details, without buttons, a deadline
     * or a request to confirm again; the status and the answer stay, and later edits of the old messages show the
     * new details. core publishes the event only for a confirmed request, so a copy still pending here only
     * missed the CONFIRMED event (delayed, or the local record of the teacher's tap failed): it is confirmed here,
     * its messages show that with the new details, and the teacher gets the same info-only update; the late
     * CONFIRMED event then changes nothing. Another teacher's or a notice's copy is left alone.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onDetailsChanged(SubstitutionDetailsChanged event) {
        requests.findForUpdate(event.changeId())
                .filter(found -> found.getTeacherId().equals(event.teacherId()) && !found.isNotice())
                .ifPresent(found -> {
                    SubstitutionRequest.Details after = SubstitutionRequest.Details.of(event);
                    if (found.isPending()) {
                        SubstitutionRequest.Details before = found.details();
                        found.changeStatus(ChangeStatus.CONFIRMED, clock.instant());
                        found.takeDetails(after, clock.instant());
                        editMessages(found, SubstitutionTexts.finalState(found, ChangeStatus.CONFIRMED), null, null,
                                null, event.eventId());
                        if (SubstitutionTexts.differs(before, after)) {
                            sendInfoUpdate(found, before, event.eventId());
                        }
                    } else if (found.isConfirmedRequest()) {
                        informOfNewDetails(found, after, event.eventId());
                    }
                });
    }

    /**
     * The info-only update of a request already confirmed: nothing when the teacher would read the same, never
     * reached him, or the lesson is over. Its own message per event (purpose SUBSTITUTION_STATUS, no buttons), so
     * neither the reminder key nor the «no longer waits for an answer» check can swallow it; {@link #onSent}
     * remembers it, so later final states edit it like the request.
     */
    private void informOfNewDetails(SubstitutionRequest request, SubstitutionRequest.Details after, UUID eventId) {
        SubstitutionRequest.Details before = request.details();
        if (!SubstitutionTexts.differs(before, after)) {
            return;
        }
        request.takeDetails(after, clock.instant());
        sendInfoUpdate(request, before, eventId);
    }

    /** «✏️ Заміну змінено» of a confirmed request whose details already moved from {@code before}. */
    private void sendInfoUpdate(SubstitutionRequest request, SubstitutionRequest.Details before, UUID eventId) {
        if (messages.findByChangeId(request.getChangeId()).isEmpty()
                || lessonOver(request.details().lessonDate(), request.details().endsAt())) {
            return;
        }
        bindings.teacherChat(request.getTeacherId()).ifPresent(chat -> outbox.enqueue(
                OutboundDraft.text(Recipient.of(chat), DeliveryPurpose.SUBSTITUTION_STATUS,
                                SubstitutionTexts.update(request, before))
                        .withKeyboard(SubstitutionTexts.updateKeyboard(request))
                        .correlatedWith(request.getChangeId())
                        .forEvent(eventId, "details")));
    }

    /**
     * {@code substitution.status-changed}: final states edit the request messages (a notice of a published
     * change keeps its buttons until the teacher answers); a refusal goes to admins. A late event that would move
     * the copy back ({@link SubstitutionRequest#canMoveTo}) changes and edits nothing.
     *
     * <p>{@code teacherName}: looked up by {@link #adminNameFor(RozkladEvent)} before the transaction (null ⇒ now).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onStatusChanged(SubstitutionStatusChanged event, String teacherName) {
        Optional<SubstitutionRequest> request = requests.findForUpdate(event.changeId());
        request.ifPresent(found -> {
            // NOTIFY: у DECLINED заміну переводить лише «Не можу» викладача — локальна копія це запамʼятовує
            boolean changed = found.isNotice() && event.status() == ChangeStatus.DECLINED
                    ? found.recordAnswer(ConfirmationDecision.DECLINE, ChangeStatus.DECLINED, clock.instant())
                    : found.changeStatus(event.status(), clock.instant());
            String text = found.isNotice() ? SubstitutionTexts.noticeState(found)
                    : SubstitutionTexts.finalState(found, event.status());
            if (changed && text != null) {
                editMessages(found, text, keyboardOf(found), null, null, event.eventId());
            }
        });
        if (event.status() == ChangeStatus.DECLINED) {
            String name = teacherName != null ? teacherName : teacherName(event.teacherId());
            String text = request
                    .map(found -> SubstitutionTexts.adminDeclined(name, found, links.adminChanges(found.getLessonDate())))
                    .orElseGet(() -> SubstitutionTexts.adminDeclinedUnknown(name, event.date(), event.groupCode(),
                            event.date() == null ? "" : links.adminChanges(event.date())));
            notifyAdmins(text, event.eventId(), "declined");
        }
    }

    /**
     * {@code substitution.teacher-replied} (NOTIFY): «Добре» or «Не можу» that kept the status, given in Telegram
     * or the cabinet. The notice messages show the answer; an objection to a published change goes to admins
     * (the order stays, the dispatcher prepares an amendment).
     *
     * <p>{@code teacherName}: looked up by {@link #adminNameFor(RozkladEvent)} before the transaction (null ⇒ now).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onTeacherReplied(SubstitutionTeacherReplied event, String teacherName) {
        String name = !event.raisesObjection() ? null
                : teacherName != null ? teacherName : teacherName(event.teacherId());
        Optional<SubstitutionRequest> request = requests.findForUpdate(event.changeId());
        request.ifPresent(found -> {
            if (found.isNotice() && found.getTeacherId().equals(event.teacherId())
                    && found.recordAnswer(event.decision(), event.status(), clock.instant())) {
                String text = SubstitutionTexts.noticeState(found);
                if (text != null) {
                    editMessages(found, text, keyboardOf(found), null, null, event.eventId());
                }
            }
        });
        if (event.raisesObjection()) {
            String text = SubstitutionTexts.adminObjection(name, event.pair(), event.groupCode(), event.date(),
                    links.adminChanges(event.date()));
            notifyAdmins(text, event.eventId(), "objection");
        }
    }

    /**
     * The teacher's name an admin text of this event needs (a refusal, an objection), or null. Called before the
     * event's transaction: on a cache miss it is an HTTP call to core, which must not run while a row is locked.
     */
    public String adminNameFor(RozkladEvent event) {
        return switch (event) {
            case SubstitutionStatusChanged changed when changed.status() == ChangeStatus.DECLINED ->
                    teacherName(changed.teacherId());
            case SubstitutionTeacherReplied replied when replied.raisesObjection() -> teacherName(replied.teacherId());
            default -> null;
        };
    }

    /**
     * Remembers ids of sent request messages and info-only updates so they can be edited later. A message
     * Telegram accepted after the change moved on (the worker had checked it just before) is corrected at once:
     * another teacher's message is retired, a cancelled or answered one shows the current state. The row lock
     * orders this with the event handlers, so either they see the message or this sees their state.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onSent(OutboundMessageSent sent) {
        // edits carry no new message id; a new SUBSTITUTION_STATUS message is the info-only update
        boolean infoUpdate = sent.purpose() == DeliveryPurpose.SUBSTITUTION_STATUS;
        if (!(sent.purpose().isSubstitutionRequest() || infoUpdate) || sent.correlationId() == null
                || sent.telegramMessageId() == null) {
            return;
        }
        Optional<SubstitutionRequest> request = requests.findForUpdate(sent.correlationId());
        if (request.isEmpty() || messages.existsByChatIdAndMessageId(sent.chatId(), sent.telegramMessageId())) {
            return;
        }
        SubstitutionRequest found = request.get();
        Optional<String> obsolete = infoUpdate ? infoObsoleteReason(found, sent.chatId())
                : obsoleteReason(found, sent.chatId());
        boolean addressee = !REASSIGNED.equals(obsolete.orElse(null));
        if (addressee) {
            messages.save(new SubstitutionMessage(sent.correlationId(), sent.chatId(), sent.telegramMessageId(),
                    clock.instant()));
        }
        if (obsolete.isEmpty()) {
            return;
        }
        String text = addressee ? SubstitutionTexts.current(found) : SubstitutionTexts.outdated(found);
        InlineKeyboard keyboard = addressee ? keyboardOf(found) : null;
        bindings.teacherBinding(sent.chatId()).ifPresent(chat -> outbox.enqueue(
                OutboundDraft.edit(Recipient.of(chat), DeliveryPurpose.SUBSTITUTION_STATUS, sent.telegramMessageId(),
                                text, keyboard)
                        .correlatedWith(found.getChangeId())
                        .dedupBy("late:" + sent.outboundId())));
    }

    /**
     * A request, notice or reminder still queued whose change went to another teacher or stopped waiting; an
     * info-only update whose change went to another teacher or is no longer confirmed (cancelled).
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<String> obsoleteReason(OutboundMessage message) {
        if (message.getCorrelationId() == null) {
            return Optional.empty();
        }
        if (message.getPurpose().isSubstitutionRequest()) {
            return requests.findById(message.getCorrelationId())
                    .flatMap(request -> obsoleteReason(request, message.getChatId()));
        }
        if (message.getPurpose() == DeliveryPurpose.SUBSTITUTION_STATUS && message.getKind() == OutboundKind.TEXT) {
            return requests.findById(message.getCorrelationId())
                    .flatMap(request -> infoObsoleteReason(request, message.getChatId()));
        }
        return Optional.empty();
    }

    private Optional<String> obsoleteReason(SubstitutionRequest request, long chatId) {
        if (reassigned(request, chatId)) {
            return Optional.of(REASSIGNED);
        }
        return request.isAwaitingAnswer() ? Optional.empty() : Optional.of(NOT_AWAITING);
    }

    /** The info-only update informs only while the request is still confirmed for this chat's teacher. */
    private Optional<String> infoObsoleteReason(SubstitutionRequest request, long chatId) {
        if (reassigned(request, chatId)) {
            return Optional.of(REASSIGNED);
        }
        return request.isConfirmedRequest() ? Optional.empty() : Optional.of(NOT_IN_FORCE);
    }

    /** The chat is no longer this teacher's: bound to another teacher, or not a teacher chat at all any more. */
    private boolean reassigned(SubstitutionRequest request, long chatId) {
        Optional<UUID> chatTeacher = bindings.teacherBinding(chatId).map(ChatBinding::getTeacherId);
        return chatTeacher.isEmpty() || !chatTeacher.get().equals(request.getTeacherId());
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
                return alreadyAnswered(changeId, decision, wanted, known);
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
        boolean notice = result != null && result.approvalMode() != null ? result.notice()
                : known.map(SubstitutionRequest::isNotice).orElse(false);
        SubstitutionRequest updated = recordReplySafely(changeId, status, notice ? decision : null, chatId,
                clickedMessageId);
        log.info("Teacher answered {} for change {} → {}", decision, changeId, status);
        return new ReplyOutcome.Done(updated != null ? updated : known.orElse(null), status, notice);
    }

    /**
     * core says the change no longer takes this answer. If it already holds exactly this answer (a
     * double tap, or the answer given from another copy of the request), the teacher gets the same
     * result again instead of «неактуальний».
     */
    private ReplyOutcome alreadyAnswered(UUID changeId, ConfirmationDecision decision, ChangeStatus wanted,
                                         Optional<SubstitutionRequest> known) {
        Optional<SubstitutionRequest> fresh = requests.findById(changeId).or(() -> known);
        if (fresh.isPresent() && fresh.get().isNotice() && fresh.get().getTeacherAnswer() == decision) {
            return new ReplyOutcome.Done(fresh.get(), fresh.get().getStatus(), true);
        }
        if (fresh.isPresent() && !fresh.get().isNotice() && fresh.get().getStatus() == wanted) {
            return new ReplyOutcome.Done(fresh.get(), wanted, false);
        }
        return new ReplyOutcome.NotPending(fresh.orElse(null));
    }

    /** {@code answer} is the notice answer (NOTIFY) or {@code null} for a request. */
    private SubstitutionRequest recordReplySafely(UUID changeId, ChangeStatus status, ConfirmationDecision answer,
                                                  long chatId, Long clickedMessageId) {
        for (int attempt = 1; attempt <= MAX_RECORD_ATTEMPTS; attempt++) {
            try {
                return transactions.execute(tx -> recordReply(changeId, status, answer, chatId, clickedMessageId));
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

    private SubstitutionRequest recordReply(UUID changeId, ChangeStatus status, ConfirmationDecision answer,
                                           long chatId, Long clickedMessageId) {
        Optional<SubstitutionRequest> request = requests.findForUpdate(changeId);
        request.ifPresent(found -> {
            if (found.isNotice()) {
                // відповідь на повідомлення: подія core (той самий стан) могла встигнути першою — тоді нічого
                if (answer != null && found.recordAnswer(answer, status, clock.instant())) {
                    String text = SubstitutionTexts.noticeState(found);
                    if (text != null) {
                        editMessages(found, text, keyboardOf(found), chatId, clickedMessageId, null);
                    }
                }
                return;
            }
            // лише очікуваний запит переходить у фінальний стан: подія core могла встигнути першою
            // (той самий статус) або вже пізніша (APPLIED) — тоді нічого не перезаписуємо
            if (found.isPending() && found.changeStatus(status, clock.instant())) {
                String text = SubstitutionTexts.finalState(found, status);
                if (text != null) {
                    editMessages(found, text, null, chatId, clickedMessageId, null);
                }
            }
        });
        return request.orElse(null);
    }

    /**
     * Deadline passed without an answer: one reminder to the teacher and a notice to admins. Requests
     * that never reached the teacher (no Telegram) were already reported by
     * {@link #reportTeachersWithoutTelegram()}; lessons already over (an earlier day, or today after the pair's
     * end) are not worth a reminder or a notice. Teacher names are
     * looked up before the rows are locked, so no call to core holds the locks.
     */
    public int remindOverdue() {
        Instant now = clock.instant();
        Map<UUID, String> names = teacherNames(requests.findOverdueTeachers(now));
        if (names.isEmpty()) {
            return 0;
        }
        return count(transactions.execute(tx -> remindOverdue(now, names)));
    }

    private int remindOverdue(Instant now, Map<UUID, String> names) {
        List<SubstitutionRequest> overdue = requests.findOverdue(now);
        for (SubstitutionRequest request : overdue) {
            request.markReminded(now);
            if (request.isWithoutTelegram() || lessonOver(request.getLessonDate(), request.details().endsAt())) {
                continue;
            }
            String key = request.getChangeId() + ":" + request.getDeadline().toEpochMilli();
            bindings.teacherChat(request.getTeacherId()).ifPresent(chat -> outbox.enqueue(
                    OutboundDraft.text(Recipient.of(chat), DeliveryPurpose.SUBSTITUTION_REMINDER,
                                    SubstitutionTexts.request(request, true))
                            .withKeyboard(SubstitutionTexts.buttons(request.getChangeId()))
                            .correlatedWith(request.getChangeId())
                            .dedupBy(reminderKey(request))));
            String text = SubstitutionTexts.adminOverdue(name(names, request.getTeacherId()), request,
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
    public int reportTeachersWithoutTelegram() {
        Instant noticedBefore = clock.instant().minus(properties.noTelegramNoticeDelay());
        Map<UUID, String> names = teacherNames(requests.findUnreportedWithoutTelegramTeachers(noticedBefore));
        if (names.isEmpty()) {
            return 0;
        }
        return count(transactions.execute(tx -> reportTeachersWithoutTelegram(noticedBefore, names)));
    }

    private int reportTeachersWithoutTelegram(Instant noticedBefore, Map<UUID, String> names) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock);
        // рядки заблоковано в порядку change id (як і в remindOverdue), групуємо ж за викладачем, датою, парою
        List<SubstitutionRequest> due = new ArrayList<>(requests.findUnreportedWithoutTelegram(noticedBefore));
        due.sort(BY_TEACHER_DATE_PAIR);
        Map<TeacherDate, List<SubstitutionRequest>> byTeacherAndDate = new LinkedHashMap<>();
        for (SubstitutionRequest request : due) {
            request.markAdminWarned(now);
            if (!request.getLessonDate().isBefore(today)) {
                byTeacherAndDate.computeIfAbsent(new TeacherDate(request.getTeacherId(), request.getLessonDate(),
                        request.isNotice()), key -> new ArrayList<>()).add(request);
            }
        }
        if (byTeacherAndDate.isEmpty()) {
            return 0;
        }
        List<ChatBinding> admins = bindings.adminChats();
        int warnings = 0;
        for (Map.Entry<TeacherDate, List<SubstitutionRequest>> entry : byTeacherAndDate.entrySet()) {
            TeacherDate key = entry.getKey();
            String text = SubstitutionTexts.adminNoTelegram(name(names, key.teacherId()), entry.getValue(),
                    links.adminChanges(key.date()));
            boolean queued = false;
            for (ChatBinding admin : admins) {
                queued |= !outbox.enqueue(OutboundDraft.text(Recipient.of(admin), DeliveryPurpose.SUBSTITUTION_ADMIN,
                                text)
                        .dedupBy("no-telegram:" + key.teacherId() + ":" + key.date() + (key.notice() ? ":notice:" : ":")
                                + admin.getChatId()))
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

    /** Buttons a message keeps in the request's state: an unanswered notice of a change in force keeps them. */
    private static InlineKeyboard keyboardOf(SubstitutionRequest request) {
        return request.isNotice() ? SubstitutionTexts.noticeKeyboard(request.getChangeId(), request.getStatus(),
                request.getTeacherAnswer()) : null;
    }

    private void editMessages(SubstitutionRequest request, String text, InlineKeyboard keyboard, Long skipChatId,
                              Long skipMessageId, UUID eventId) {
        for (SubstitutionMessage message : messages.findByChangeId(request.getChangeId())) {
            if (skipChatId != null && skipChatId == message.getChatId() && skipMessageId != null
                    && skipMessageId == message.getMessageId()) {
                continue;
            }
            Optional<ChatBinding> chat = bindings.teacherBinding(message.getChatId());
            if (chat.isEmpty() || !request.getTeacherId().equals(chat.get().getTeacherId())) {
                // лише повідомлення поточного адресата: чуже вже відкликане як неактуальне
                continue;
            }
            OutboundDraft draft = OutboundDraft.edit(Recipient.of(chat.get()), DeliveryPurpose.SUBSTITUTION_STATUS,
                    message.getMessageId(), text, keyboard).correlatedWith(request.getChangeId());
            outbox.enqueue(eventId != null
                    ? draft.forEvent(eventId, "edit-" + message.getMessageId())
                    : draft.dedupBy("reply:" + request.getChangeId() + ":" + message.getMessageId() + ":"
                            + request.getStatus() + (request.isNotice() ? ":" + request.getTeacherAnswer() : "")));
        }
    }

    /** The reminder of one deadline round: core's re-sent request and {@link #remindOverdue()} share it. */
    private static String reminderKey(SubstitutionRequest request) {
        return "reminder:" + request.getChangeId() + ":" + request.getDeadline().toEpochMilli();
    }

    /** The lesson ended: an earlier day, or today after its end (when the end time is known). */
    private boolean lessonOver(LocalDate date, LocalTime endsAt) {
        LocalDate today = LocalDate.now(clock);
        return date.isBefore(today)
                || date.equals(today) && endsAt != null && !endsAt.isAfter(LocalTime.now(clock));
    }

    private void notifyAdmins(String text, UUID eventId, String part) {
        for (ChatBinding admin : bindings.adminChats()) {
            outbox.enqueue(OutboundDraft.text(Recipient.of(admin), DeliveryPurpose.SUBSTITUTION_ADMIN, text)
                    .forEvent(eventId, part));
        }
    }

    private Map<UUID, String> teacherNames(List<UUID> teacherIds) {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (UUID teacherId : teacherIds) {
            names.put(teacherId, teacherName(teacherId));
        }
        return names;
    }

    /** A name looked up before locking; a teacher who appeared in between is looked up now. */
    private String name(Map<UUID, String> names, UUID teacherId) {
        String name = names.get(teacherId);
        return name != null ? name : teacherName(teacherId);
    }

    private static int count(Integer value) {
        return value == null ? 0 : value;
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

    private record TeacherDate(UUID teacherId, LocalDate date, boolean notice) {
    }

    /** Result of a teacher's answer, for the bot's reply. */
    public sealed interface ReplyOutcome {

        /**
         * {@code request} is null when the change was never seen by notification; {@code notice}: the change is
         * a NOTIFY assignment (the answer kept or declined a change in force).
         */
        record Done(SubstitutionRequest request, ChangeStatus status, boolean notice) implements ReplyOutcome {
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
