package ua.vtc.rozklad.core.shared.messaging;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Delivery of the outbox ({@code app.outbox.*}).
 *
 * @param confirmTimeout     how long a send waits for the broker's publisher confirm before the publication
 *                           counts as failed
 * @param resubmitInterval   how often failed publications are sent again while core runs
 * @param resubmitMinAge     a failed publication younger than this waits for the next round (lets a broker
 *                           restart finish)
 * @param completedRetention completed publications older than this are deleted nightly
 */
@Validated
@ConfigurationProperties("app.outbox")
public record OutboxProperties(
        @NotNull @DefaultValue("30s") Duration confirmTimeout,
        @NotNull @DefaultValue("1m") Duration resubmitInterval,
        @NotNull @DefaultValue("30s") Duration resubmitMinAge,
        @NotNull @DefaultValue("7d") Duration completedRetention) {
}
