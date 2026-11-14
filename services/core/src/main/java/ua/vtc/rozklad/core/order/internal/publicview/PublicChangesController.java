package ua.vtc.rozklad.core.order.internal.publicview;

import java.time.Clock;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.settings.PublicHistory;

/**
 * Changes for students and parents (CONTRACTS §1, no authentication). Visitors see the public history
 * window ({@link PublicHistory}) and the future; signed-in staff see every date, so the answer varies by
 * {@code Authorization} for caches, and a staff answer is {@code no-cache, private} like the schedule's. A visitor's
 * answer sets no Cache-Control of its own (Spring Security's {@code no-store} default, as before).
 */
@RestController
class PublicChangesController {

    private static final CacheControl REVALIDATE_PRIVATE = CacheControl.noCache().cachePrivate();

    private final PublicChanges changes;
    private final PublicHistory history;
    private final Clock clock;

    PublicChangesController(PublicChanges changes, PublicHistory history, Clock clock) {
        this.changes = changes;
        this.history = history;
        this.clock = clock;
    }

    /** Published changes of the date; today when {@code date} is omitted. */
    @GetMapping("/api/public/changes")
    ResponseEntity<PublicChangesDto> changes(@RequestParam(required = false)
                                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now(clock);
        history.requireDate(day);
        ResponseEntity.BodyBuilder answer = ResponseEntity.ok().varyBy(HttpHeaders.AUTHORIZATION);
        if (history.fullArchive()) {
            answer.cacheControl(REVALIDATE_PRIVATE);
        }
        return answer.body(changes.of(day));
    }
}
