package ua.vtc.rozklad.scheduler.generation;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.scheduler.config.SchedulerProperties;

class JobTerminationTest {

    private final SchedulerProperties properties = new SchedulerProperties(Duration.ofSeconds(2),
            Duration.ofMinutes(15), Duration.ofSeconds(15), 0.25, 5, Duration.ofSeconds(30), Duration.ofDays(30));

    @Test
    void stopsAtTheLimitOrAfterAQuarterOfItWithoutImprovement() {
        TerminationConfig termination = JobTermination.forLimit(Duration.ofSeconds(90), properties);

        assertThat(termination.getSpentLimit()).isEqualTo(Duration.ofSeconds(90));
        assertThat(termination.getUnimprovedSpentLimit()).isEqualTo(Duration.ofMillis(22_500));
    }

    @Test
    void longJobsMayPlateauLonger() {
        assertThat(JobTermination.forLimit(Duration.ofMinutes(10), properties).getUnimprovedSpentLimit())
                .isEqualTo(Duration.ofSeconds(150));
    }

    @Test
    void shortJobsRunToTheirLimit() {
        TerminationConfig termination = JobTermination.forLimit(Duration.ofSeconds(10), properties);

        assertThat(termination.getSpentLimit()).isEqualTo(Duration.ofSeconds(10));
        assertThat(termination.getUnimprovedSpentLimit()).isNull();
        assertThat(JobTermination.unimprovedLimit(Duration.ofSeconds(30), properties)).isEqualTo(Duration.ofSeconds(15));
    }
}
