package ua.vtc.rozklad.core.calendar.web;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.calendar.internal.CalendarPeriodImportDtos.CalendarPeriodImportRequest;
import ua.vtc.rozklad.core.calendar.internal.CalendarPeriodImportDtos.CalendarPeriodImportResult;
import ua.vtc.rozklad.core.calendar.internal.CalendarPeriodImportService;

/**
 * {@code POST /api/admin/import/calendar-periods[?dryRun=true]} (ADMIN or the importer's SERVICE account):
 * the periods and theory weeks of the college's academic calendar, upserted by source key in one transaction.
 * {@code Idempotency-Key} is accepted and ignored: the upsert itself is idempotent.
 */
@RestController
class CalendarPeriodImportController {

    private final CalendarPeriodImportService imports;

    CalendarPeriodImportController(CalendarPeriodImportService imports) {
        this.imports = imports;
    }

    @PostMapping("/api/admin/import/calendar-periods")
    CalendarPeriodImportResult importPeriods(@RequestParam(defaultValue = "false") boolean dryRun,
                                             @Valid @RequestBody CalendarPeriodImportRequest request) {
        return dryRun ? imports.preview(request) : imports.apply(request);
    }
}
