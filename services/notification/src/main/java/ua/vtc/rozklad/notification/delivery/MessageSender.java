package ua.vtc.rozklad.notification.delivery;

import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.delivery.MediaResolver.Media;
import ua.vtc.rozklad.notification.telegram.TelegramApi;
import ua.vtc.rozklad.notification.telegram.TelegramApiFailure;

/** Performs the Telegram call for one queued message. */
@Component
public class MessageSender {

    private final TelegramApi telegram;
    private final MediaResolver media;

    public MessageSender(TelegramApi telegram, MediaResolver media) {
        this.telegram = telegram;
        this.media = media;
    }

    /** @return Telegram message id of a new message, {@code null} for an edit */
    public Long send(OutboundMessage message) {
        return switch (message.getKind()) {
            case TEXT -> telegram.sendText(message.getChatId(), message.getText(), message.getReplyMarkup())
                    .messageId();
            case PHOTO -> {
                Media picture = media.resolve(message.getMediaRef());
                yield telegram.sendPhoto(message.getChatId(), picture.bytes(), picture.fileName(), message.getText())
                        .messageId();
            }
            case EDIT_TEXT -> {
                try {
                    telegram.editText(message.getChatId(), message.getEditMessageId(), message.getText(),
                            message.getReplyMarkup());
                } catch (TelegramApiFailure failure) {
                    // повідомлення вже в потрібному стані — це успіх, а не помилка
                    if (!failure.isMessageNotModified()) {
                        throw failure;
                    }
                }
                yield null;
            }
        };
    }
}
