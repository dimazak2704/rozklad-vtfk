package ua.vtc.rozklad.notification.support;

import java.net.URI;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import ua.vtc.rozklad.notification.config.MailChannelProperties;
import ua.vtc.rozklad.notification.config.ServiceUrlsProperties;
import ua.vtc.rozklad.notification.email.MailChannel;

/** {@code app.mail.*} bound like Spring does it (defaults included), for unit tests. */
public final class MailTestProperties {

    private MailTestProperties() {
    }

    /** @param values {@code key, value, key, value…} below {@code app.mail} */
    public static MailChannelProperties of(String... values) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            map.put("app.mail." + values[i], values[i + 1]);
        }
        map.putIfAbsent("app.mail.host", "");
        return new Binder(new MapConfigurationPropertySource(map)).bindOrCreate("app.mail",
                MailChannelProperties.class);
    }

    public static ServiceUrlsProperties urls(String publicWebUrl) {
        return new ServiceUrlsProperties(URI.create("http://core"), URI.create("http://importer"),
                URI.create(publicWebUrl));
    }

    public static MailChannel channel(MailChannelProperties properties, String publicWebUrl) {
        return new MailChannel(properties, urls(publicWebUrl), Clock.systemUTC());
    }
}
