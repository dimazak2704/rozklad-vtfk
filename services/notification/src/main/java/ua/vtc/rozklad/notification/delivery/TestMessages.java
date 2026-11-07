package ua.vtc.rozklad.notification.delivery;

import org.springframework.stereotype.Service;
import ua.vtc.rozklad.notification.binding.BindingType;
import ua.vtc.rozklad.notification.binding.ChatBinding;
import ua.vtc.rozklad.notification.binding.ChatBindings;
import ua.vtc.rozklad.notification.config.TelegramProperties;
import ua.vtc.rozklad.notification.web.BadGatewayException;
import ua.vtc.rozklad.notification.web.BusinessRuleException;
import ua.vtc.rozklad.notification.web.ServiceUnavailableException;

/** {@code POST /api/notify/test}: one message to {@code TELEGRAM_OWNER_CHAT_ID}, sent right away and logged. */
@Service
public class TestMessages {

    public static final String TEXT = "Тест: бот Розклад ВТФК підключено ✅";

    private final TelegramProperties telegram;
    private final ChatBindings bindings;
    private final Outbox outbox;
    private final DeliveryWorker worker;

    public TestMessages(TelegramProperties telegram, ChatBindings bindings, Outbox outbox, DeliveryWorker worker) {
        this.telegram = telegram;
        this.bindings = bindings;
        this.outbox = outbox;
        this.worker = worker;
    }

    /** @return the logged delivery (SENT, or RETRYING when Telegram was temporarily unreachable) */
    public OutboundMessage send() {
        if (!telegram.enabled()) {
            throw new ServiceUnavailableException("Telegram-бот вимкнено: не задано TELEGRAM_BOT_TOKEN.");
        }
        if (!telegram.hasOwnerChat()) {
            throw new BusinessRuleException("owner-chat-missing",
                    "Не задано TELEGRAM_OWNER_CHAT_ID — нікому надіслати тестове повідомлення.");
        }
        long ownerChatId = telegram.ownerChatId();
        bindings.ensureOwnerAdminChat(ownerChatId);
        ChatBinding owner = bindings.find(ownerChatId, BindingType.ADMIN_CHAT).orElseThrow();
        OutboundMessage queued = outbox.enqueue(OutboundDraft.text(Recipient.of(owner), DeliveryPurpose.TEST, TEXT))
                .getFirst();
        OutboundMessage result = worker.deliverNow(queued.getId());
        if (result.getStatus() == OutboundStatus.FAILED) {
            throw new BadGatewayException("Telegram не прийняв тестове повідомлення: " + result.getLastError());
        }
        return result;
    }
}
