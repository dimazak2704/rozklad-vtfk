package ua.vtc.rozklad.notification.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.notification.config.TelegramProperties;
import ua.vtc.rozklad.notification.email.MailChannel;
import ua.vtc.rozklad.notification.support.MailTestProperties;
import ua.vtc.rozklad.notification.substitution.SubstitutionJobs;

/** The every-minute substitution jobs run one after another in one scheduled method. */
class ScheduledJobsTest {

    private final List<String> calls = new ArrayList<>();

    @Test
    void deadlinesThenTeachersWithoutTelegramInOneRun() {
        jobs(substitutions(false), "123:token").substitutionDeadlines();
        assertThat(calls).containsExactly("remindOverdue telegram=true", "reportTeachersWithoutTelegram telegram=true");
    }

    @Test
    void failureOfTheFirstJobDoesNotSkipTheSecond() {
        jobs(substitutions(true), "123:token").substitutionDeadlines();
        assertThat(calls).containsExactly("remindOverdue telegram=true", "reportTeachersWithoutTelegram telegram=true");
    }

    @Test
    void nothingRunsWithoutTelegramAndEmail() {
        jobs(substitutions(false), "", mail("")).substitutionDeadlines();
        assertThat(calls).isEmpty();
    }

    @Test
    void emailAloneRunsTheJobsWithoutQueueingTelegram() {
        // the bot is off: letters still remind teachers and dispatchers, nothing waits in the Telegram queue
        jobs(substitutions(false), "", mail("mailpit")).substitutionDeadlines();
        assertThat(calls).containsExactly("remindOverdue telegram=false",
                "reportTeachersWithoutTelegram telegram=false");
    }

    private ScheduledJobs jobs(SubstitutionJobs substitutions, String botToken) {
        return jobs(substitutions, botToken, mail(""));
    }

    private ScheduledJobs jobs(SubstitutionJobs substitutions, String botToken, MailChannel mail) {
        TelegramProperties telegram = new TelegramProperties(botToken, "scheduler_vtfk_bot", null,
                TelegramProperties.Mode.POLLING, null, null, TelegramProperties.Access.BOUND_ONLY);
        return new ScheduledJobs(substitutions, null, null, null, null, telegram, Clock.systemUTC(), null, mail,
                null);
    }

    private static MailChannel mail(String host) {
        return MailTestProperties.channel(MailTestProperties.of("host", host, "from", "rozklad@rozklad.local"),
                "http://localhost:8088");
    }

    /** Records the calls; the jobs themselves are covered by {@code SubstitutionFlowIT}. */
    private SubstitutionJobs substitutions(boolean firstFails) {
        return new SubstitutionJobs(null, null, null, null, null, null, null, null) {
            @Override
            public int remindOverdue(boolean telegram) {
                calls.add("remindOverdue telegram=" + telegram);
                if (firstFails) {
                    throw new IllegalStateException("deadlock detected");
                }
                return 0;
            }

            @Override
            public int reportTeachersWithoutTelegram(boolean telegram) {
                calls.add("reportTeachersWithoutTelegram telegram=" + telegram);
                return 0;
            }
        };
    }
}
