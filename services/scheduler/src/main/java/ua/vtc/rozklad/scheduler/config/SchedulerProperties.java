package ua.vtc.rozklad.scheduler.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Generator settings ({@code app.scheduler.*}).
 *
 * @param progressInterval         how often {@code generation.progress} may be sent per job
 * @param maxTimeLimit             longest time limit a request may ask for (longer requests fail)
 * @param minUnimprovedLimit       a job also stops after this long without a better score…
 * @param unimprovedRatio          …or after this share of its time limit, whichever is longer
 * @param disciplineRepeatWeight   soft weight of «a discipline three times a day» (the request has none)
 * @param completionResendInterval how often unsent {@code generation.completed} events are retried
 * @param retention                how long finished jobs and processed event ids are kept
 */
@Validated
@ConfigurationProperties("app.scheduler")
public record SchedulerProperties(
        @NotNull Duration progressInterval,
        @NotNull Duration maxTimeLimit,
        @NotNull Duration minUnimprovedLimit,
        @DecimalMin("0.0") @DecimalMax("1.0") double unimprovedRatio,
        @PositiveOrZero int disciplineRepeatWeight,
        @NotNull Duration completionResendInterval,
        @NotNull Duration retention) {
}
