package ua.vtc.rozklad.notification.delivery;

import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineKeyboard;

/**
 * A message to put into the outbound queue. {@code dedupKey} makes enqueueing idempotent, e.g.
 * {@code <eventId>:<chatId>:rows} — a redelivered event never sends the same message twice.
 */
public record OutboundDraft(Recipient recipient, DeliveryPurpose purpose, OutboundKind kind, String text,
                            InlineKeyboard keyboard, String mediaRef, Long editMessageId, UUID correlationId,
                            UUID orderId, UUID eventId, String dedupKey) {

    public OutboundDraft {
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(kind, "kind");
        switch (kind) {
            case TEXT -> requireText(text);
            case PHOTO -> Objects.requireNonNull(mediaRef, "mediaRef");
            case EDIT_TEXT -> {
                requireText(text);
                Objects.requireNonNull(editMessageId, "editMessageId");
            }
        }
    }

    public static OutboundDraft text(Recipient recipient, DeliveryPurpose purpose, String html) {
        return new OutboundDraft(recipient, purpose, OutboundKind.TEXT, html, null, null, null, null, null, null,
                null);
    }

    public static OutboundDraft photo(Recipient recipient, DeliveryPurpose purpose, String mediaRef,
                                      String captionHtml) {
        return new OutboundDraft(recipient, purpose, OutboundKind.PHOTO, captionHtml, null, mediaRef, null, null,
                null, null, null);
    }

    public static OutboundDraft edit(Recipient recipient, DeliveryPurpose purpose, long messageId, String html,
                                     InlineKeyboard keyboard) {
        return new OutboundDraft(recipient, purpose, OutboundKind.EDIT_TEXT, html, keyboard, null, messageId, null,
                null, null, null);
    }

    public OutboundDraft withKeyboard(InlineKeyboard value) {
        return new OutboundDraft(recipient, purpose, kind, text, value, mediaRef, editMessageId, correlationId,
                orderId, eventId, dedupKey);
    }

    public OutboundDraft correlatedWith(UUID value) {
        return new OutboundDraft(recipient, purpose, kind, text, keyboard, mediaRef, editMessageId, value, orderId,
                eventId, dedupKey);
    }

    public OutboundDraft forOrder(UUID value) {
        return new OutboundDraft(recipient, purpose, kind, text, keyboard, mediaRef, editMessageId, correlationId,
                value, eventId, dedupKey);
    }

    /** Ties the message to an event and makes it unique per (event, chat, part). */
    public OutboundDraft forEvent(UUID value, String part) {
        return new OutboundDraft(recipient, purpose, kind, text, keyboard, mediaRef, editMessageId, correlationId,
                orderId, value, value + ":" + recipient.chatId() + ":" + part);
    }

    public OutboundDraft dedupBy(String value) {
        return new OutboundDraft(recipient, purpose, kind, text, keyboard, mediaRef, editMessageId, correlationId,
                orderId, eventId, value);
    }

    private static void requireText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("message text is empty");
        }
    }
}
