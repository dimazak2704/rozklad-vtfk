package ua.vtc.rozklad.notification.text;

import java.net.URI;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.config.ServiceUrlsProperties;

/**
 * Links to the SPA ({@code PUBLIC_WEB_URL}) inside bot messages. Telegram rejects or ignores
 * links to local hosts, so on a dev machine messages simply carry no link.
 */
@Component
public class WebLinks {

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "0.0.0.0", "[::1]");

    private final String base;
    private final boolean linkable;

    public WebLinks(ServiceUrlsProperties urls) {
        String url = urls.publicWebUrl().toString();
        this.base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.linkable = isPublic(urls.publicWebUrl());
    }

    /** {@code \n<a href="…/changes?date=2026-10-19">label</a>} or an empty string on a local host. */
    public String line(String path, String label) {
        if (!linkable) {
            return "";
        }
        return "\n" + Html.link(base + path, label);
    }

    public String changes(LocalDate date) {
        return line("/changes?date=" + date, "Відкрити заміни на сайті");
    }

    public String adminChanges(LocalDate date) {
        return line("/admin/changes?date=" + date, "Відкрити в панелі диспетчера");
    }

    public String adminGeneration() {
        return line("/admin/generation", "Відкрити генератор");
    }

    static boolean isPublic(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return !LOCAL_HOSTS.contains(normalized) && normalized.contains(".") && !normalized.endsWith(".localhost");
    }
}
