package ua.vtc.rozklad.notification.delivery;

import java.util.UUID;

/**
 * Published (in the transaction that marks the message SENT) after Telegram accepted a queued
 * message; {@code telegramMessageId} is null for edits.
 */
public record OutboundMessageSent(UUID outboundId, DeliveryPurpose purpose, UUID correlationId, long chatId,
                                  Long telegramMessageId) {
}
