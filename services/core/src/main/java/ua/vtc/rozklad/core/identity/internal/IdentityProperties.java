package ua.vtc.rozklad.core.identity.internal;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Identity settings ({@code app.identity.*}).
 *
 * @param inviteTtl how long an invite link stays valid (7 days by default)
 */
@Validated
@ConfigurationProperties("app.identity")
public record IdentityProperties(@NotNull @DefaultValue("7d") Duration inviteTtl) {
}
