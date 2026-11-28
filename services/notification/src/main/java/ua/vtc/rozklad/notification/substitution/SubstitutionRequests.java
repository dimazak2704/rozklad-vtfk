package ua.vtc.rozklad.notification.substitution;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.ConfirmationDecision;
import ua.vtc.rozklad.contracts.SubstitutionRequestMode;
import ua.vtc.rozklad.contracts.events.RozkladEvent;
import ua.vtc.rozklad.contracts.events.SubstitutionConfirmationRequested;
import ua.vtc.rozklad.contracts.events.SubstitutionDetailsChanged;
import ua.vtc.rozklad.contracts.events.SubstitutionStatusChanged;
import ua.vtc.rozklad.contracts.events.SubstitutionTeacherReplied;
import ua.vtc.rozklad.notification.binding.ChatBinding;
import ua.vtc.rozklad.notification.delivery.DeliveryPurpose;
import ua.vtc.rozklad.notification.delivery.OutboundDraft;
import ua.vtc.rozklad.notification.delivery.Recipient;
import ua.vtc.rozklad.notification.email.EmailKind;
import ua.vtc.rozklad.notification.email.EmailNotices;
import ua.vtc.rozklad.notification.text.WebLinks;

/**
 * Substitutions in Telegram, driven by core's events: sends the request (CONFIRM) or the notice of an assigned
 * change (NOTIFY) to the teacher's chat, edits the messages to the state core reports and keeps admin chats
 * informed of a refusal or an objection to a published change. The teacher's own answer is
 * {@link SubstitutionReplies}, the deadline reminder and the warnings about teachers without Telegram are
 * {@link SubstitutionJobs}, the last check of a queued message is {@link SubstitutionDeliveries}.
 *
 * <p>The local copy of a request is written by these events and by the teacher's button at the same time. Every
 * writer reads the row with a lock and only moves a pending request to a final state, so the writers converge
 * to the same state and neither of them fails.
 *
 * <p>The same moments also write e-mail intents ({@link SubstitutionMails}), whether or not the teacher has
 * Telegram: the notice or request, a change of what he reads, the cancellation or reassignment, the deadline
 * reminder, and for the dispatchers the refusal and the objection.
 */
@Service
public class SubstitutionRequests {

    private final SubstitutionRequestRepository requests;
    private final SubstitutionChats chats;
    private final SubstitutionMails mails;
    private final TeacherNames names;
    private final WebLinks links;
    private final Clock clock;

    SubstitutionRequests(SubstitutionRequestRepository requests, SubstitutionChats chats, SubstitutionMails mails,
                         TeacherNames names, WebLinks links, Clock clock) {
        this.requests = requests;
        this.chats = chats;
        this.mails = mails;
        this.names = names;
        this.links = links;
        this.clock = clock;
    }

    /**
     * {@code substitution.confirmation-requested}. A repeated request for a pending change is a reminder; when
     * what the teacher sees changed (core re-sends it after the dispatcher edited the room) it is
     * «✏️ Заміну змінено» instead, a request or notice again with the same deadline. Nothing is sent about a
     * lesson that is already over: neither a notice (NOTIFY) nor a request or its reminder (CONFIRM). A teacher
     * without Telegram is not reported right away: {@link SubstitutionJobs#reportTeachersWithoutTelegram()}
     * tells admins once per teacher and date about requests and notices that are still unanswered.
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
                && chats.hasMessages(event.changeId());
        SubstitutionRequest.Details before = told ? existing.get().details() : null;
        boolean sameDeadlinePassed = told && event.deadline() != null && !event.deadline().isAfter(now)
                && Objects.equals(existing.get().getDeadline(), event.deadline());
        // e-mail does not depend on Telegram: the teacher "holds" the change while it still waits for him
        boolean mailTold = sameTeacher && existing.get().getMode() == event.effectiveMode()
                && (notice ? existing.get().isNoticeInForce() : existing.get().isPending());
        SubstitutionRequest.Details mailBefore = mailTold ? existing.get().details() : null;
        boolean mailDeadlinePassed = mailTold && event.deadline() != null && !event.deadline().isAfter(now)
                && Objects.equals(existing.get().getDeadline(), event.deadline());
        if (existing.isPresent() && !sameTeacher) {
            chats.retireMessages(existing.get(), event.eventId());
            // the previous teacher hears by e-mail too (only if a letter about the change reached him)
            mails.toTeacher(EmailKind.SUBSTITUTION_CANCELLED, existing.get(), existing.get().getTeacherId(), null,
                    true, event.eventId(), EmailNotices.eventKey(event.eventId(), "reassigned"));
        }
        SubstitutionRequest request = existing.map(found -> {
            found.refresh(event, now);
            return found;
        }).orElseGet(() -> requests.save(SubstitutionRequest.from(event, now)));
        boolean updated = told && SubstitutionTexts.differs(before, request.details());
        boolean reminder = told && !notice && !updated;
        // core's deadline reminder (the same, already passed deadline, nothing else changed) is the round
        // SubstitutionJobs.remindOverdue() reminds about too: both use one dedup key, so the teacher gets one
        // reminder whichever comes first. An update (a new room) is its own message and is never swallowed by that key.
        boolean deadlineReminder = reminder && sameDeadlinePassed;

        if (lessonOver(event.date(), event.endsAt())) {
            // пара вже минула (і сьогоднішня теж): ні повідомляти, ні просити, ні нагадувати нема про що —
            // диспетчер вносить це для обліку
            return;
        }
        mailTeacher(request, notice, mailTold, mailBefore, mailDeadlinePassed, event.eventId());
        Optional<ChatBinding> chat = chats.teacherChat(event.teacherId());
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
            chats.enqueue(deadlineReminder ? draft.dedupBy(SubstitutionJobs.reminderKey(request))
                    : draft.forEvent(event.eventId(), "request"));
            return;
        }
        request.markNoTelegram(now);
    }

    /**
     * The teacher's letter of a {@code confirmation-requested}: a notice or request when the change is new to him,
     * «Заміну змінено» when what he reads changed, a reminder at the passed deadline; a pure repeat writes nothing.
     */
    private void mailTeacher(SubstitutionRequest request, boolean notice, boolean told,
                             SubstitutionRequest.Details before, boolean deadlinePassed, UUID eventId) {
        boolean updated = told && SubstitutionTexts.differs(before, request.details());
        EmailKind kind;
        if (!told) {
            kind = notice ? EmailKind.SUBSTITUTION_NOTICE : EmailKind.SUBSTITUTION_REQUEST;
        } else if (updated) {
            kind = EmailKind.SUBSTITUTION_CHANGED;
        } else if (!notice && deadlinePassed) {
            kind = EmailKind.SUBSTITUTION_REMINDER;
        } else {
            return;
        }
        mails.toTeacher(kind, request, request.getTeacherId(),
                updated ? SubstitutionTexts.changeSummary(before, request.details()) : null, false, eventId,
                kind == EmailKind.SUBSTITUTION_REMINDER ? SubstitutionJobs.reminderKey(request) + ":mail"
                        : EmailNotices.eventKey(eventId, "request"));
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
                        chats.editMessages(found, SubstitutionTexts.finalState(found, ChangeStatus.CONFIRMED), null,
                                null, null, event.eventId());
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
     * neither the reminder key nor the «no longer waits for an answer» check can swallow it;
     * {@link SubstitutionDeliveries#onSent} remembers it, so later final states edit it like the request.
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
        if (lessonOver(request.details().lessonDate(), request.details().endsAt())) {
            return;
        }
        mails.toTeacher(EmailKind.SUBSTITUTION_CHANGED, request, request.getTeacherId(),
                SubstitutionTexts.changeSummary(before, request.details()), false, eventId,
                EmailNotices.eventKey(eventId, "details"));
        if (!chats.hasMessages(request.getChangeId())) {
            return;
        }
        chats.teacherChat(request.getTeacherId()).ifPresent(chat -> chats.enqueue(
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
                chats.editMessages(found, text, SubstitutionChats.keyboardOf(found), null, null, event.eventId());
            }
            if (changed && event.status() == ChangeStatus.CANCELLED) {
                mails.toTeacher(EmailKind.SUBSTITUTION_CANCELLED, found, found.getTeacherId(), null, false,
                        event.eventId(), EmailNotices.eventKey(event.eventId(), "cancelled"));
            }
        });
        if (event.status() == ChangeStatus.DECLINED) {
            String name = teacherName != null ? teacherName : teacherName(event.teacherId());
            String text = request
                    .map(found -> SubstitutionTexts.adminDeclined(name, found, links.adminChanges(found.getLessonDate())))
                    .orElseGet(() -> SubstitutionTexts.adminDeclinedUnknown(name, event.date(), event.groupCode(),
                            event.date() == null ? "" : links.adminChanges(event.date())));
            chats.notifyAdmins(text, event.eventId(), "declined");
            mails.toAdmins(EmailKind.SUBSTITUTION_DECLINED, name, event.changeId(), request.orElse(null),
                    event.date(), null, event.groupCode(), null, event.eventId(),
                    EmailNotices.eventKey(event.eventId(), "declined"));
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
                    chats.editMessages(found, text, SubstitutionChats.keyboardOf(found), null, null, event.eventId());
                }
            }
        });
        if (event.raisesObjection()) {
            String text = SubstitutionTexts.adminObjection(name, event.pair(), event.groupCode(), event.date(),
                    links.adminChanges(event.date()));
            chats.notifyAdmins(text, event.eventId(), "objection");
            mails.toAdmins(EmailKind.SUBSTITUTION_OBJECTION, name, event.changeId(), request.orElse(null),
                    event.date(), event.pair(), event.groupCode(), null, event.eventId(),
                    EmailNotices.eventKey(event.eventId(), "objection"));
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

    @Transactional(readOnly = true)
    public Optional<SubstitutionRequest> find(UUID changeId) {
        return requests.findById(changeId);
    }

    private boolean lessonOver(LocalDate date, LocalTime endsAt) {
        return LessonTime.isOver(clock, date, endsAt);
    }

    private String teacherName(UUID teacherId) {
        return names.of(teacherId);
    }
}
