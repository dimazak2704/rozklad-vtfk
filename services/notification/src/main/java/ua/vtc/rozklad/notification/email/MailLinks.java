package ua.vtc.rozklad.notification.email;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.config.ServiceUrlsProperties;

/**
 * Absolute links in letters: {@code PUBLIC_WEB_URL} plus a fixed path and UUID or date values, never text of
 * users. Unlike Telegram, e-mail accepts local links, so the development stand gets working links in Mailpit.
 * No link carries a token: every page asks for sign-in.
 */
@Component
public class MailLinks {

    private final String base;

    public MailLinks(ServiceUrlsProperties urls) {
        String url = urls.publicWebUrl().toString();
        this.base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** The cabinet scrolled to the change ({@code /me?change=…}). */
    public String cabinet(UUID changeId) {
        return changeId == null ? cabinet() : base + "/me?change=" + changeId;
    }

    public String cabinet() {
        return base + "/me";
    }

    public String settings(EmailAudience audience) {
        return audience == EmailAudience.ADMIN ? base + "/admin/notifications" : base + "/me/notifications";
    }

    public String adminGeneration(UUID jobId) {
        return base + "/admin/generation" + (jobId == null ? "" : "?job=" + jobId);
    }

    public String adminChanges(LocalDate date) {
        return base + "/admin/changes" + (date == null ? "" : "?date=" + date);
    }

    public String changes(LocalDate date) {
        return base + "/changes" + (date == null ? "" : "?date=" + date);
    }

    public String orderPdf(UUID orderId) {
        return base + "/api/documents/orders/" + orderId + ".pdf";
    }
}
