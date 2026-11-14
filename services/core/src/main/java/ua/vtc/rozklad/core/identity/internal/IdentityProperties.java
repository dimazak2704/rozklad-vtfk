package ua.vtc.rozklad.core.identity.internal;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Identity settings ({@code app.identity.*}).
 *
 * @param inviteTtl       how long a teacher's invite link stays valid (7 days by default)
 * @param passwordLinkTtl how long a password link of «Доступи» stays valid (24 hours by default): it sets the
 *                        password of an existing account, an admin's too, so it lives shorter than an invite
 */
@Validated
@ConfigurationProperties("app.identity")
public record IdentityProperties(@NotNull @DefaultValue("7d") Duration inviteTtl,
                                 @NotNull @DefaultValue("24h") Duration passwordLinkTtl) {
}
