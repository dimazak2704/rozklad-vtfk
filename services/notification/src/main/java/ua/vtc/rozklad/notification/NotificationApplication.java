package ua.vtc.rozklad.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Telegram bot (@scheduler_vtfk_bot), chat bindings, rate-limited delivery queue and order
 * documents (DOCX → PDF → PNG). Consumes core events from {@code notification.q.events}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
