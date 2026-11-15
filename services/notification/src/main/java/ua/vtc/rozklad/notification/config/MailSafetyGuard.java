package ua.vtc.rozklad.notification.config;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Keeps a development stand away from real mailboxes. The stand is recognised by its plain-http web address
 * ({@code PUBLIC_WEB_URL}); there the channel works only with a local SMTP catcher (Mailpit), unless
 * {@code MAIL_ALLOW_REAL_SMTP=true} says a real server is meant for this one run. Production (https) with the
 * catcher is allowed but suspicious: nobody would read those letters.
 */
public final class MailSafetyGuard {

    /** Hosts of a local SMTP catcher: the compose service name and the loopback addresses. */
    static final Set<String> LOCAL_HOSTS = Set.of("mailpit", "localhost", "127.0.0.1", "::1", "[::1]");

    private MailSafetyGuard() {
    }

    public enum Mode {
        /** No host, or refused by the guard. */
        OFF,
        /** A local catcher (Mailpit): nothing leaves the machine. */
        DEV_CATCHER,
        /** A real SMTP server. */
        SMTP
    }

    /** @param problem Ukrainian reason when the channel is off although a host is set, or a warning; else null */
    public record Verdict(Mode mode, String problem) {

        public boolean enabled() {
            return mode != Mode.OFF;
        }
    }

    public static Verdict check(MailChannelProperties mail, URI publicWebUrl) {
        if (!mail.configured()) {
            return new Verdict(Mode.OFF, null);
        }
        boolean local = isLocal(mail.host());
        boolean developmentStand = publicWebUrl == null || !"https".equalsIgnoreCase(publicWebUrl.getScheme());
        if (local) {
            return new Verdict(Mode.DEV_CATCHER, developmentStand ? null
                    : "Листи йдуть у локальний перехоплювач (" + mail.host() + "), а не адресатам.");
        }
        if (developmentStand && !mail.allowRealSmtp()) {
            return new Verdict(Mode.OFF, "Стенд розробки (http) не надсилає листи на справжній SMTP-сервер "
                    + mail.host() + ". Використайте Mailpit або свідомо задайте MAIL_ALLOW_REAL_SMTP=true.");
        }
        return new Verdict(Mode.SMTP, null);
    }

    static boolean isLocal(String host) {
        return LOCAL_HOSTS.contains(host.strip().toLowerCase(Locale.ROOT));
    }
}
