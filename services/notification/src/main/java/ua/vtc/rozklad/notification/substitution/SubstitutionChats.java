package ua.vtc.rozklad.notification.substitution;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.binding.ChatBinding;
import ua.vtc.rozklad.notification.binding.ChatBindings;
import ua.vtc.rozklad.notification.delivery.DeliveryPurpose;
import ua.vtc.rozklad.notification.delivery.OutboundDraft;
import ua.vtc.rozklad.notification.delivery.OutboundMessage;
import ua.vtc.rozklad.notification.delivery.Outbox;
import ua.vtc.rozklad.notification.delivery.Recipient;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineKeyboard;

/**
 * The Telegram side of a substitution: the teacher's and the admins' chats, the outbound queue, and the request
 * messages already sent to the teacher, which later states edit or retire.
 */
@Component
class SubstitutionChats {

    private final SubstitutionMessageRepository messages;
    private final ChatBindings bindings;
    private final Outbox outbox;

    SubstitutionChats(SubstitutionMessageRepository messages, ChatBindings bindings, Outbox outbox) {
        this.messages = messages;
        this.bindings = bindings;
        this.outbox = outbox;
    }

    Optional<ChatBinding> teacherChat(UUID teacherId) {
        return bindings.teacherChat(teacherId);
    }

    List<ChatBinding> adminChats() {
        return bindings.adminChats();
    }

    /** @return the queued rows; empty when a row with the same dedup key already exists */
    List<OutboundMessage> enqueue(OutboundDraft draft) {
        return outbox.enqueue(draft);
    }

    /** A request, notice or info-only update of the change reached a teacher chat and can be edited later. */
    boolean hasMessages(UUID changeId) {
        return !messages.findByChangeId(changeId).isEmpty();
    }

    /** The change went to another teacher: the previous one's messages lose their buttons. */
    void retireMessages(SubstitutionRequest request, UUID eventId) {
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

    /**
     * Edits the current addressee's messages of the change to {@code text}, skipping the message the teacher
     * tapped ({@code skipChatId}/{@code skipMessageId}). With {@code eventId} the edits are keyed by the event;
     * without it (the teacher's own answer) by the state they show.
     */
    void editMessages(SubstitutionRequest request, String text, InlineKeyboard keyboard, Long skipChatId,
                      Long skipMessageId, UUID eventId) {
        for (SubstitutionMessage message : messages.findByChangeId(request.getChangeId())) {
            if (skipChatId != null && skipChatId == message.getChatId() && skipMessageId != null
                    && skipMessageId == message.getMessageId()) {
                continue;
            }
            Optional<ChatBinding> chat = bindings.teacherBinding(message.getChatId());
            if (chat.isEmpty() || !request.getTeacherId().equals(chat.get().getTeacherId())) {
                // only the current addressee's messages: another teacher's were already retired as outdated
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

    void notifyAdmins(String text, UUID eventId, String part) {
        for (ChatBinding admin : bindings.adminChats()) {
            outbox.enqueue(OutboundDraft.text(Recipient.of(admin), DeliveryPurpose.SUBSTITUTION_ADMIN, text)
                    .forEvent(eventId, part));
        }
    }

    /** Buttons a message keeps in the request's state: an unanswered notice of a change in force keeps them. */
    static InlineKeyboard keyboardOf(SubstitutionRequest request) {
        return request.isNotice() ? SubstitutionTexts.noticeKeyboard(request.getChangeId(), request.getStatus(),
                request.getTeacherAnswer()) : null;
    }
}
