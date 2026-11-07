package ua.vtc.rozklad.core.order.internal.publicview;

import java.time.Clock;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Changes for students and parents (CONTRACTS §1, no authentication). */
@RestController
class PublicChangesController {

    private final PublicChanges changes;
    private final Clock clock;

    PublicChangesController(PublicChanges changes, Clock clock) {
        this.changes = changes;
        this.clock = clock;
    }

    /** Published changes of the date; today when {@code date} is omitted. */
    @GetMapping("/api/public/changes")
    PublicChangesDto changes(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                             LocalDate date) {
        return changes.of(date != null ? date : LocalDate.now(clock));
    }
}
