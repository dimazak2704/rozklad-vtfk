package ua.vtc.rozklad.core.order.internal.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ua.vtc.rozklad.core.substitution.SubstitutionDaySummary;

class DashboardAlertsTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 13);

    @ParameterizedTest
    @CsvSource({
            "1, 1 заміна довше встановленого часу чекає підтвердження",
            "2, 2 заміни довше встановленого часу чекають підтвердження",
            "5, 5 замін довше встановленого часу чекають підтвердження",
            "21, 21 заміна довше встановленого часу чекає підтвердження"
    })
    void overduePendingVerbAgreesWithTheCount(int overdue, String message) {
        SubstitutionDaySummary summary = new SubstitutionDaySummary(DATE, 0, 0, 0, overdue, 0, 0, overdue,
                List.of(), List.of());

        List<DashboardDto.Alert> alerts = Dashboard.alerts(DATE, true, summary);

        assertThat(alerts).singleElement().satisfies(alert -> {
            assertThat(alert.kind()).isEqualTo("PENDING_OVERDUE");
            assertThat(alert.message()).isEqualTo(message);
        });
    }
}
