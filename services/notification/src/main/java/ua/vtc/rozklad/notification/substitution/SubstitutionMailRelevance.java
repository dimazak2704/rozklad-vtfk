package ua.vtc.rozklad.notification.substitution;

import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.notification.email.EmailMessage;
import ua.vtc.rozklad.notification.email.EmailRelevance;

/**
 * The last check of a substitution letter, against the local copy of the change (like
 * {@link SubstitutionRequests#obsoleteReason} for Telegram): a notice, request or reminder is not sent once the
 * change went to another teacher or no longer waits for this teacher's answer (he answered in Telegram during the
 * settle delay, it was cancelled); a changed letter is not sent once the change is no longer in force. A
 * cancellation always goes (it only follows a letter that reached the teacher).
 */
@Component
class SubstitutionMailRelevance implements EmailRelevance {

    private final SubstitutionRequestRepository requests;

    SubstitutionMailRelevance(SubstitutionRequestRepository requests) {
        this.requests = requests;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> obsoleteReason(EmailMessage message) {
        if (!message.getKind().followsChange() || message.getCorrelationId() == null) {
            return Optional.empty();
        }
        Optional<SubstitutionRequest> found = requests.findById(message.getCorrelationId());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        SubstitutionRequest request = found.get();
        boolean reassigned = message.getTeacherId() != null && !message.getTeacherId().equals(request.getTeacherId());
        return switch (message.getKind()) {
            case SUBSTITUTION_NOTICE, SUBSTITUTION_REQUEST, SUBSTITUTION_REMINDER -> reassigned
                    ? Optional.of(SubstitutionRequests.REASSIGNED)
                    : request.isAwaitingAnswer() ? Optional.empty() : Optional.of(SubstitutionRequests.NOT_AWAITING);
            case SUBSTITUTION_CHANGED -> reassigned
                    ? Optional.of(SubstitutionRequests.REASSIGNED)
                    : inForce(request) ? Optional.empty() : Optional.of(SubstitutionRequests.NOT_IN_FORCE);
            default -> Optional.empty();
        };
    }

    /** Pending, confirmed or published: a declined or cancelled change needs no news about its room. */
    private static boolean inForce(SubstitutionRequest request) {
        return request.getStatus() == ChangeStatus.PENDING_CONFIRMATION || request.getStatus() == ChangeStatus.CONFIRMED
                || request.getStatus() == ChangeStatus.APPLIED;
    }
}
