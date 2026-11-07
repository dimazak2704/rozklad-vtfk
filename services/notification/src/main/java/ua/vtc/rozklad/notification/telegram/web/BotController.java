package ua.vtc.rozklad.notification.telegram.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.notification.delivery.TestMessages;
import ua.vtc.rozklad.notification.delivery.web.DeliveryController.DeliveryDto;
import ua.vtc.rozklad.notification.telegram.BotStatus;

/** Bot status and the test message for the admin panel (CONTRACTS §6). */
@RestController
@RequestMapping("/api/notify")
@Tag(name = "Telegram-бот")
public class BotController {

    private final BotStatus botStatus;
    private final TestMessages testMessages;

    public BotController(BotStatus botStatus, TestMessages testMessages) {
        this.botStatus = botStatus;
        this.testMessages = testMessages;
    }

    @GetMapping("/bot")
    @Operation(summary = "Стан бота: ім’я, посилання, режим, чи підключено")
    public BotStatus.Status bot() {
        return botStatus.status();
    }

    @PostMapping("/test")
    @Operation(summary = "Тестове повідомлення в чат власника (TELEGRAM_OWNER_CHAT_ID)")
    public DeliveryDto test() {
        return DeliveryDto.of(testMessages.send());
    }
}
