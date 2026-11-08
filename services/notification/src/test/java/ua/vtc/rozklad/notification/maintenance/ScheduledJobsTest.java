package ua.vtc.rozklad.notification.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.notification.config.TelegramProperties;
import ua.vtc.rozklad.notification.substitution.SubstitutionRequests;

/** The every-minute substitution jobs run one after another in one scheduled method. */
class ScheduledJobsTest {

    private final List<String> calls = new ArrayList<>();

    @Test
    void deadlinesThenTeachersWithoutTelegramInOneRun() {
        jobs(substitutions(false), "123:token").substitutionDeadlines();
        assertThat(calls).containsExactly("remindOverdue", "reportTeachersWithoutTelegram");
    }

    @Test
    void failureOfTheFirstJobDoesNotSkipTheSecond() {
        jobs(substitutions(true), "123:token").substitutionDeadlines();
        assertThat(calls).containsExactly("remindOverdue", "reportTeachersWithoutTelegram");
    }

    @Test
    void nothingRunsWithoutTelegram() {
        jobs(substitutions(false), "").substitutionDeadlines();
        assertThat(calls).isEmpty();
    }

    private ScheduledJobs jobs(SubstitutionRequests substitutions, String botToken) {
        TelegramProperties telegram = new TelegramProperties(botToken, "scheduler_vtfk_bot", null,
                TelegramProperties.Mode.POLLING, null, null, TelegramProperties.Access.BOUND_ONLY);
        return new ScheduledJobs(substitutions, null, null, null, null, telegram, Clock.systemUTC());
    }

    /** Records the calls; the jobs themselves are covered by {@code SubstitutionFlowIT}. */
    private SubstitutionRequests substitutions(boolean firstFails) {
        return new SubstitutionRequests(null, null, null, null, null, null, null, null, null, null) {
            @Override
            public int remindOverdue() {
                calls.add("remindOverdue");
                if (firstFails) {
                    throw new IllegalStateException("deadlock detected");
                }
                return 0;
            }

            @Override
            public int reportTeachersWithoutTelegram() {
                calls.add("reportTeachersWithoutTelegram");
                return 0;
            }
        };
    }
}
