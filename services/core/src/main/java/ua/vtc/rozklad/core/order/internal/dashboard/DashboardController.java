package ua.vtc.rozklad.core.order.internal.dashboard;

import java.time.Clock;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Dispatcher dashboard (CONTRACTS §2). */
@RestController
class DashboardController {

    private final Dashboard dashboard;
    private final Clock clock;

    DashboardController(Dashboard dashboard, Clock clock) {
        this.dashboard = dashboard;
        this.clock = clock;
    }

    /** Figures of the date; today when {@code date} is omitted. */
    @GetMapping("/api/admin/dashboard")
    DashboardDto dashboard(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                           LocalDate date) {
        return dashboard.of(date != null ? date : LocalDate.now(clock));
    }
}
