package ua.vtc.rozklad.notification.telegram;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.telegram.telegrambots.meta.api.objects.Update;
import ua.vtc.rozklad.notification.config.TelegramProperties;
import ua.vtc.rozklad.notification.web.FieldValidationException;
import ua.vtc.rozklad.notification.web.ForbiddenOperationException;
import ua.vtc.rozklad.notification.web.NotFoundException;

/**
 * {@code POST /api/notify/telegram/webhook} (WEBHOOK mode). Open in the security rules: the caller
 * is authenticated by {@code X-Telegram-Bot-Api-Secret-Token}. The mode and the secret are checked before the
 * body is read, and at most {@value #MAX_UPDATE_BYTES} bytes are read (a Telegram update is a few kilobytes):
 * an anonymous client cannot make the service buffer megabytes, least of all in POLLING mode, where the path is
 * of no use. The body is parsed with Jackson 2, the mapper of the Telegram library's types.
 */
@Hidden
@RestController
public class TelegramWebhookController {

    static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";
    static final int MAX_UPDATE_BYTES = 256 * 1024;

    private final TelegramProperties telegram;
    private final TelegramBotRunner runner;
    private final ObjectMapper telegramJson = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public TelegramWebhookController(TelegramProperties telegram, TelegramBotRunner runner) {
        this.telegram = telegram;
        this.runner = runner;
    }

    @PostMapping("/api/notify/telegram/webhook")
    public ResponseEntity<Void> receive(@RequestHeader(name = SECRET_HEADER, required = false) String secret,
                                        HttpServletRequest request) throws IOException {
        if (!telegram.enabled() || telegram.mode() != TelegramProperties.Mode.WEBHOOK) {
            throw new NotFoundException("Вебхук Telegram вимкнено.");
        }
        if (!secretMatches(secret)) {
            throw new ForbiddenOperationException("Невірний секрет вебхука Telegram.");
        }
        byte[] body = request.getContentLengthLong() > MAX_UPDATE_BYTES ? null
                : request.getInputStream().readNBytes(MAX_UPDATE_BYTES + 1);
        if (body == null || body.length > MAX_UPDATE_BYTES) {
            throw new FieldValidationException("body", "Оновлення Telegram завелике.");
        }
        Update update;
        try {
            update = telegramJson.readValue(body, Update.class);
        } catch (JsonProcessingException e) {
            throw new FieldValidationException("body", "Некоректне оновлення Telegram.");
        }
        UpdateMapper.map(update).ifPresent(runner::submit);
        return ResponseEntity.ok().build();
    }

    private boolean secretMatches(String secret) {
        String expected = telegram.webhookSecret();
        if (expected == null || expected.isBlank() || secret == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8));
    }
}
