package ua.vtc.rozklad.notification.email.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.notification.config.TokenRoles;
import ua.vtc.rozklad.notification.email.EmailAudience;
import ua.vtc.rozklad.notification.email.EmailKind;
import ua.vtc.rozklad.notification.email.EmailSettings;
import ua.vtc.rozklad.notification.email.MailAddresses;
import ua.vtc.rozklad.notification.web.BusinessRuleException;

/**
 * The caller's own e-mail settings (TEACHER, ADMIN; CONTRACTS §6). The account is always the token's {@code sub}.
 */
@RestController
@RequestMapping("/api/notify/email/me")
@Tag(name = "Листи на пошту")
public class EmailPreferencesController {

    private final EmailSettings settings;

    public EmailPreferencesController(EmailSettings settings) {
        this.settings = settings;
    }

    @GetMapping
    @Operation(summary = "Мої налаштування листів: адреса, її статус і види листів")
    public EmailSettingsDto mine(@AuthenticationPrincipal Jwt jwt) {
        return EmailSettingsDto.of(settings.of(accountId(jwt), TokenRoles.of(jwt)));
    }

    @PutMapping
    @Operation(summary = "Зберегти налаштування листів (422 email-kind-not-allowed для чужого виду листів)")
    public EmailSettingsDto update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateRequest body) {
        return EmailSettingsDto.of(settings.update(accountId(jwt), TokenRoles.of(jwt), body.enabled(), body.kinds(),
                body.version()));
    }

    @PostMapping("/test")
    @Operation(summary = "Надіслати тестовий лист на мою підтверджену адресу (не частіше 3 разів на годину)")
    public ResponseEntity<TestLetterDto> test(@AuthenticationPrincipal Jwt jwt) {
        settings.sendTest(accountId(jwt));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new TestLetterDto("QUEUED"));
    }

    static UUID accountId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessRuleException("account-unknown", "Обліковий запис не розпізнано. Увійдіть знову.");
        }
    }

    /** {@code kinds}: only the kinds to change; {@code version}: from GET, optional (409 when outdated). */
    public record UpdateRequest(@NotNull Boolean enabled, Map<String, Boolean> kinds, Long version) {
    }

    public record TestLetterDto(String status) {
    }

    /**
     * {@code address}: the caller's own address, unmasked; {@code addressStatus} VERIFIED, UNVERIFIED or MISSING;
     * {@code verifiedBy} GOOGLE or TRUSTED_DOMAIN.
     */
    public record EmailSettingsDto(ChannelDto channel, String address, MailAddresses.Status addressStatus,
                                   MailAddresses.VerifiedBy verifiedBy, boolean enabled, Long version,
                                   List<KindDto> kinds) {

        static EmailSettingsDto of(EmailSettings.View view) {
            return new EmailSettingsDto(new ChannelDto(view.channelEnabled(), view.channelReason()),
                    view.address().address(), view.address().status(), view.address().verifiedBy(), view.enabled(),
                    view.version(), view.kinds().stream().map(KindDto::of).toList());
        }
    }

    public record ChannelDto(boolean enabled, String reason) {
    }

    public record KindDto(EmailKind kind, EmailAudience audience, String label, String description, boolean enabled,
                          boolean defaultEnabled, boolean switchable) {

        static KindDto of(EmailSettings.KindSetting setting) {
            EmailKind kind = setting.kind();
            return new KindDto(kind, kind.audience(), kind.label(), kind.description(), setting.enabled(),
                    kind.defaultOn(), kind.switchable());
        }
    }
}
