package ua.vtc.rozklad.notification.email.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.notification.config.MailChannelProperties;
import ua.vtc.rozklad.notification.config.MailSafetyGuard;
import ua.vtc.rozklad.notification.email.EmailKind;
import ua.vtc.rozklad.notification.email.EmailMessage;
import ua.vtc.rozklad.notification.email.EmailMessageRepository;
import ua.vtc.rozklad.notification.email.EmailMessageStatus;
import ua.vtc.rozklad.notification.email.MailAddresses;
import ua.vtc.rozklad.notification.email.MailChannel;
import ua.vtc.rozklad.notification.shared.PageResponse;

/** The e-mail channel for the dispatcher (ADMIN): server status and the letter log with masked addresses. */
@RestController
@RequestMapping("/api/notify/email")
@Validated
@Tag(name = "Листи на пошту")
public class EmailAdminController {

    private final MailChannel channel;
    private final EmailMessageRepository messages;

    public EmailAdminController(MailChannel channel, EmailMessageRepository messages) {
        this.channel = channel;
        this.messages = messages;
    }

    @GetMapping("/status")
    @Operation(summary = "Стан каналу листів (без логіна й пароля SMTP)")
    public StatusDto status() {
        MailChannelProperties properties = channel.properties();
        boolean configured = properties.configured();
        String tls = !configured ? null : properties.ssl() ? "SSL" : properties.starttls() ? "STARTTLS" : "NONE";
        return new StatusDto(channel.enabled(), channel.mode(), configured ? properties.host() : null,
                configured ? properties.port() : null, display(channel.from()), display(channel.replyTo()), tls,
                channel.problem());
    }

    /** «Розклад ВТФК <rozklad@vtc.vn.ua>» as typed in MAIL_FROM (no MIME quoting). */
    private static String display(jakarta.mail.internet.InternetAddress address) {
        if (address == null) {
            return null;
        }
        String name = address.getPersonal();
        return name == null || name.isBlank() ? address.getAddress() : name + " <" + address.getAddress() + ">";
    }

    @GetMapping("/deliveries")
    @Transactional(readOnly = true)
    @Operation(summary = "Журнал листів (нові спершу), фільтри status і kind; адреси замасковано")
    public PageResponse<EmailDeliveryDto> deliveries(@RequestParam(required = false) EmailMessageStatus status,
                                                     @RequestParam(required = false) EmailKind kind,
                                                     @RequestParam(defaultValue = "0") @Min(0) int page,
                                                     @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        return PageResponse.of(messages.search(status, kind, PageRequest.of(page, size)), EmailDeliveryDto::of);
    }

    /** {@code mode}: OFF, DEV_CATCHER (Mailpit) or SMTP; {@code tls}: STARTTLS, SSL or NONE. */
    public record StatusDto(boolean enabled, MailSafetyGuard.Mode mode, String host, Integer port, String from,
                            String replyTo, String tls, String problem) {
    }

    /** {@code to} is masked («k•••@vtc.vn.ua»); {@code error} is the last failure or why the letter was not sent. */
    public record EmailDeliveryDto(UUID id, EmailKind kind, String to, String accountName, String subject,
                                   EmailMessageStatus status, int attempts, String error, Instant createdAt,
                                   Instant sentAt, Instant nextAttemptAt) {

        static EmailDeliveryDto of(EmailMessage message) {
            Instant next = message.getStatus().isPending()
                    ? (message.getNextAttemptAt().isAfter(message.getNotBefore()) ? message.getNextAttemptAt()
                    : message.getNotBefore())
                    : null;
            return new EmailDeliveryDto(message.getId(), message.getKind(), MailAddresses.mask(message.getToAddress()),
                    message.getAccountName(), message.getSubject(), message.getStatus(), message.getAttempts(),
                    message.getLastError(), message.getCreatedAt(), message.getSentAt(), next);
        }
    }
}
