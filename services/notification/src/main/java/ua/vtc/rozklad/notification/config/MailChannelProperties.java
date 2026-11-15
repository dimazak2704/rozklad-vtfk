package ua.vtc.rozklad.notification.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The e-mail channel ({@code app.mail.*}, env {@code MAIL_*}). An empty {@code host} switches the channel off:
 * no intents are written and nothing is sent. The password is never logged ({@link #toString()}).
 *
 * @param host               SMTP server; empty = e-mail off (the default on a host run and in production)
 * @param port               587 (STARTTLS) or 465 (implicit TLS); Mailpit listens on 1025
 * @param username           SMTP login; empty = no SMTP AUTH (Mailpit)
 * @param password           SMTP password (Google Workspace: an app password)
 * @param starttls           require STARTTLS (port 587)
 * @param ssl                implicit TLS (port 465)
 * @param from               {@code From}, e.g. {@code Розклад ВТФК <rozklad@vtc.vn.ua>}; required when host is set
 * @param replyTo            optional {@code Reply-To} (the dispatcher office)
 * @param trustedDomains     an account address on these domains counts as verified without Google sign-in
 * @param allowRealSmtp      a deliberate one-off: lets a development stand ({@code http} web URL) use a real server
 * @param messageIdDomain    right part of {@code Message-ID}; empty = the domain of {@code from}
 * @param workerEnabled      run the background worker (off in tests: they drain explicitly)
 * @param pollInterval       pause between polls of the worker
 * @param batchSize          intents and letters claimed per poll
 * @param lease              a claimed row stays invisible to other workers this long (longer than the SMTP timeouts)
 * @param maxAttempts        failed attempts before a letter (or an intent whose recipients cannot be resolved) is FAILED
 * @param backoffBase        first retry delay; doubles with every attempt
 * @param backoffMax         upper bound of the retry delay
 * @param maxAge             a letter not sent within this time is FAILED as stale
 * @param settleDelay        substitution letters wait this long, so a quick correction replaces the first letter
 * @param connectTimeout     SMTP connect timeout
 * @param readTimeout        SMTP read timeout
 * @param writeTimeout       SMTP write timeout
 * @param globalPerSecond    letters per second for the whole service
 * @param recipientPerHour   letters per hour to one address (token bucket)
 * @param recipientBurst     bucket capacity of one address
 * @param recipientPerDay    a hard cap per address and day: a runaway loop stops here
 * @param testLettersPerHour test letters one account may ask for per hour
 * @param recipientsCacheTtl how long the recipients read from core are cached
 * @param retention          sent, failed and superseded letters are deleted after this time
 */
@Validated
@ConfigurationProperties("app.mail")
public record MailChannelProperties(
        String host,
        @DefaultValue("587") @Min(1) @Max(65535) int port,
        String username,
        String password,
        @DefaultValue("true") boolean starttls,
        @DefaultValue("false") boolean ssl,
        String from,
        String replyTo,
        @DefaultValue("vtc.vn.ua") List<String> trustedDomains,
        @DefaultValue("false") boolean allowRealSmtp,
        String messageIdDomain,
        @DefaultValue("true") boolean workerEnabled,
        @DefaultValue("2s") @NotNull Duration pollInterval,
        @DefaultValue("10") @Min(1) @Max(100) int batchSize,
        @DefaultValue("2m") @NotNull Duration lease,
        @DefaultValue("6") @Min(1) @Max(20) int maxAttempts,
        @DefaultValue("30s") @NotNull Duration backoffBase,
        @DefaultValue("30m") @NotNull Duration backoffMax,
        @DefaultValue("12h") @NotNull Duration maxAge,
        @DefaultValue("2m") @NotNull Duration settleDelay,
        @DefaultValue("10s") @NotNull Duration connectTimeout,
        @DefaultValue("20s") @NotNull Duration readTimeout,
        @DefaultValue("20s") @NotNull Duration writeTimeout,
        @DefaultValue("2") @Min(1) int globalPerSecond,
        @DefaultValue("20") @Min(1) int recipientPerHour,
        @DefaultValue("5") @Min(1) int recipientBurst,
        @DefaultValue("60") @Min(1) int recipientPerDay,
        @DefaultValue("3") @Min(1) int testLettersPerHour,
        @DefaultValue("5m") @NotNull Duration recipientsCacheTtl,
        @DefaultValue("30d") @NotNull Duration retention) {

    public MailChannelProperties {
        trustedDomains = trustedDomains == null ? List.of() : trustedDomains.stream()
                .map(domain -> domain == null ? "" : domain.strip().toLowerCase(Locale.ROOT))
                .filter(domain -> !domain.isEmpty())
                .toList();
    }

    /** A host is configured (the channel may still be refused by {@code MailSafetyGuard}). */
    public boolean configured() {
        return host != null && !host.isBlank();
    }

    public boolean hasCredentials() {
        return username != null && !username.isBlank();
    }

    @Override
    public String toString() {
        return "MailChannelProperties[host=" + (configured() ? host : "<empty>") + ", port=" + port
                + ", username=" + (hasCredentials() ? "***" : "<empty>") + ", password=***, starttls=" + starttls
                + ", ssl=" + ssl + ", from=" + from + ", replyTo=" + replyTo + ", trustedDomains=" + trustedDomains
                + ", allowRealSmtp=" + allowRealSmtp + "]";
    }
}
