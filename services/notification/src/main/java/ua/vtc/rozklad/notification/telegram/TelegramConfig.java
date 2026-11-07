package ua.vtc.rozklad.notification.telegram;

import java.time.Duration;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import ua.vtc.rozklad.notification.config.TelegramProperties;

/** The Bot API client; without a token the service runs with a {@link DisabledTelegramApi}. */
@Configuration(proxyBeanMethods = false)
public class TelegramConfig {

    @Bean
    TelegramApi telegramApi(TelegramProperties properties) {
        if (!properties.enabled()) {
            return new DisabledTelegramApi();
        }
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(40))
                .writeTimeout(Duration.ofSeconds(40))
                .callTimeout(Duration.ofSeconds(60))
                .build();
        return new OkHttpTelegramApi(new OkHttpTelegramClient(http, properties.botToken()), properties.botToken());
    }
}
