package ua.vtc.rozklad.core.accounting.internal.web;

import java.time.Clock;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Hours accounting for the dispatcher (CONTRACTS §2 «Accounting»). */
@RestController
@RequestMapping("/api/admin/accounting")
class AccountingController {

    private final AccountingReport report;
    private final Clock clock;

    AccountingController(AccountingReport report, Clock clock) {
        this.report = report;
        this.clock = clock;
    }

    /** Plan / fact / forecast; the active semester when {@code semesterId} is omitted. */
    @GetMapping
    List<AccountingRowDto> rows(@RequestParam(required = false) UUID semesterId,
                                @RequestParam(required = false) UUID groupId) {
        return report.rows(semesterId, groupId);
    }

    /** Monthly teacher statistics, {@code month=2026-10}; the current month by default. */
    @GetMapping("/teachers")
    List<TeacherStatsDto> teachers(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM")
                                   YearMonth month) {
        return report.teachers(month != null ? month : YearMonth.now(clock));
    }
}
