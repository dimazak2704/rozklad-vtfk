package ua.vtc.rozklad.scheduler.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Generator settings ({@code app.scheduler.*}).
 *
 * @param progressInterval         how often {@code generation.progress} may be sent per job
 * @param maxTimeLimit             longest time limit a request may ask for (longer requests fail)
 * @param minUnimprovedLimit       a job with a time limit also stops after this long without a better score…
 * @param unimprovedRatio          …or after this share of its time limit, whichever is longer
 * @param disciplineRepeatWeight   soft weight of «a discipline three times a day» (the request has none)
 * @param completionResendInterval how often unsent {@code generation.completed} events are retried
 * @param retention                how long finished jobs and processed event ids are kept
 * @param auto                     when a job without a time limit (auto mode) stops
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
        @NotNull Duration retention,
        @Valid @NotNull Auto auto) {

    /**
     * Auto mode ({@code app.scheduler.auto.*}): a job without a time limit stops by itself when the timetable stops
     * getting better. See {@code JobTermination#auto}.
     *
     * @param timeCap        safety cap: the longest a job may run; the dispatcher is never asked about it
     * @param window         with 0 hard, the job stops when over this window…
     * @param minImprovement …the best score improved by less than this many soft points
     * @param stuckLimit     whatever the hard score, the job stops after this long without any better score (hard
     *                       violations that cannot be removed); keep it below core's watchdog grace
     */
    public record Auto(@NotNull Duration timeCap, @NotNull Duration window, @Positive int minImprovement,
                       @NotNull Duration stuckLimit) {
    }
}
