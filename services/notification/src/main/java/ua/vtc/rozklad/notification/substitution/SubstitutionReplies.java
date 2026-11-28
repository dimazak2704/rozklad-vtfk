package ua.vtc.rozklad.notification.substitution;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.ConfirmationDecision;
import ua.vtc.rozklad.notification.binding.ChatBinding;
import ua.vtc.rozklad.notification.binding.ChatBindings;
import ua.vtc.rozklad.notification.core.CoreClient;
import ua.vtc.rozklad.notification.core.CoreDtos.ChangeSummary;
import ua.vtc.rozklad.notification.core.UpstreamRejectedException;
import ua.vtc.rozklad.notification.core.UpstreamUnavailableException;

/**
 * The teacher's ✅/❌ (or 👍/❌ under a notice) in Telegram: core decides (core
 * {@code POST /api/internal/changes/{id}/reply}), then the local copy and the other messages of the change follow.
 *
 * <p>The local copy is written by this answer and by core's events at the same time (core publishes
 * {@code substitution.status-changed} for the very answer the button sent). Both read the row with a lock and
 * only move a pending request to a final state, so they converge to the same state and neither of them fails.
 */
@Service
public class SubstitutionReplies {

    private static final Logger log = LoggerFactory.getLogger(SubstitutionReplies.class);
    private static final int MAX_RECORD_ATTEMPTS = 3;

    private final SubstitutionRequestRepository requests;
    private final ChatBindings bindings;
    private final CoreClient core;
    private final SubstitutionChats chats;
    private final TransactionTemplate transactions;
    private final Clock clock;

    SubstitutionReplies(SubstitutionRequestRepository requests, ChatBindings bindings, CoreClient core,
                        SubstitutionChats chats, TransactionTemplate transactions, Clock clock) {
        this.requests = requests;
        this.bindings = bindings;
        this.core = core;
        this.chats = chats;
        this.transactions = transactions;
        this.clock = clock;
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
                // an answer to a notice: core's event (the same state) may have come first, then nothing changes
                if (answer != null && found.recordAnswer(answer, status, clock.instant())) {
                    String text = SubstitutionTexts.noticeState(found);
                    if (text != null) {
                        chats.editMessages(found, text, SubstitutionChats.keyboardOf(found), chatId,
                                clickedMessageId, null);
                    }
                }
                return;
            }
            // only a pending request moves to a final state: core's event may have come first (the same
            // status) or already later (APPLIED), then nothing is overwritten
            if (found.isPending() && found.changeStatus(status, clock.instant())) {
                String text = SubstitutionTexts.finalState(found, status);
                if (text != null) {
                    chats.editMessages(found, text, null, chatId, clickedMessageId, null);
                }
            }
        });
        return request.orElse(null);
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
