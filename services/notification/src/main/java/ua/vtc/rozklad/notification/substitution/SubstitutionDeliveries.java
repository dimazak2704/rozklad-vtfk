package ua.vtc.rozklad.notification.substitution;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.notification.binding.ChatBinding;
import ua.vtc.rozklad.notification.binding.ChatBindings;
import ua.vtc.rozklad.notification.delivery.DeliveryPurpose;
import ua.vtc.rozklad.notification.delivery.OutboundDraft;
import ua.vtc.rozklad.notification.delivery.OutboundKind;
import ua.vtc.rozklad.notification.delivery.OutboundMessage;
import ua.vtc.rozklad.notification.delivery.OutboundMessageSent;
import ua.vtc.rozklad.notification.delivery.OutboundRelevance;
import ua.vtc.rozklad.notification.delivery.Outbox;
import ua.vtc.rozklad.notification.delivery.Recipient;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineKeyboard;

/**
 * A substitution message on its way through the outbound queue. A request, notice or reminder can wait there
 * (rate limits, Telegram retries) while the change moves on: the worker asks {@link #obsoleteReason(OutboundMessage)}
 * right before sending, and a message Telegram accepted in that window is corrected at once by
 * {@link #onSent(OutboundMessageSent)}, which also remembers the message so later states can edit it.
 */
@Component
class SubstitutionDeliveries implements OutboundRelevance {

    static final String REASSIGNED = "Неактуальне: заміну передано іншому викладачу.";
    static final String NOT_AWAITING = "Неактуальне: заміна вже не чекає відповіді.";
    static final String NOT_IN_FORCE = "Неактуальне: заміна вже не в силі.";

    private final SubstitutionRequestRepository requests;
    private final SubstitutionMessageRepository messages;
    private final ChatBindings bindings;
    private final Outbox outbox;
    private final Clock clock;

    SubstitutionDeliveries(SubstitutionRequestRepository requests, SubstitutionMessageRepository messages,
                           ChatBindings bindings, Outbox outbox, Clock clock) {
        this.requests = requests;
        this.messages = messages;
        this.bindings = bindings;
        this.outbox = outbox;
        this.clock = clock;
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
        InlineKeyboard keyboard = addressee ? SubstitutionChats.keyboardOf(found) : null;
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
}
