package ua.vtc.rozklad.core.timetable.web;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.settings.PublicHistory;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.timetable.ScheduleOwnerType;
import ua.vtc.rozklad.core.timetable.ScheduleQueries;
import ua.vtc.rozklad.core.timetable.ScheduleView;
import ua.vtc.rozklad.core.timetable.internal.schedule.IcsExportService;

/**
 * Public schedules (no login): {@code GET /api/public/schedule/{group|teacher|room}/{id}} and the
 * calendar subscription {@code GET /api/public/ics/{group|teacher}/{id}.ics} (CONTRACTS.md §1). Both overlay
 * substitutions, so visitors get whole weeks from the public history window on ({@link PublicHistory}) and
 * staff get everything: answers vary by {@code Authorization}, and staff answers are private to caches.
 */
@RestController
@RequestMapping("/api/public")
class PublicScheduleController {

    static final MediaType TEXT_CALENDAR = new MediaType("text", "calendar", StandardCharsets.UTF_8);
    private static final CacheControl REVALIDATE = CacheControl.noCache().cachePublic();
    private static final CacheControl REVALIDATE_PRIVATE = CacheControl.noCache().cachePrivate();

    private final ScheduleQueries schedules;
    private final IcsExportService icsExport;
    private final PublicHistory history;
    private final Clock clock;

    PublicScheduleController(ScheduleQueries schedules, IcsExportService icsExport, PublicHistory history,
                             Clock clock) {
        this.schedules = schedules;
        this.icsExport = icsExport;
        this.history = history;
        this.clock = clock;
    }

    /** Without {@code from}/{@code to} the current week (Monday to Sunday) is returned. */
    @GetMapping("/schedule/{type}/{id}")
    ResponseEntity<ScheduleView> schedule(@PathVariable String type, @PathVariable UUID id,
                                          @RequestParam(required = false)
                                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required = false)
                                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        ScheduleOwnerType owner = ScheduleOwnerType.fromPathSegment(type)
                .orElseThrow(() -> new NotFoundException(
                        "Невідомий тип розкладу «%s»: очікується group, teacher або room".formatted(type)));
        ScheduleRange range = ScheduleRange.resolve(from, to, clock);
        history.requireScheduleFrom(range.from());
        return ResponseEntity.ok()
                .cacheControl(cacheControl(history.fullArchive()))
                .varyBy(HttpHeaders.AUTHORIZATION)
                .body(schedules.schedule(owner, id, range.from(), range.to()));
    }

    @GetMapping("/ics/{type}/{id}.ics")
    ResponseEntity<byte[]> calendar(@PathVariable String type, @PathVariable UUID id) {
        ScheduleOwnerType owner = ScheduleOwnerType.fromPathSegment(type)
                .filter(found -> found != ScheduleOwnerType.ROOM)
                .orElseThrow(() -> new NotFoundException(
                        "Календар доступний лише для group і teacher, а не «%s»".formatted(type)));
        Optional<LocalDate> limit = history.scheduleLimit();
        IcsExportService.IcsFile file = icsExport.export(owner, id, limit.orElse(null));
        return ResponseEntity.ok()
                .contentType(TEXT_CALENDAR)
                .cacheControl(cacheControl(limit.isEmpty()))
                .varyBy(HttpHeaders.AUTHORIZATION)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(file.fileName()).build().toString())
                .body(file.content().getBytes(StandardCharsets.UTF_8));
    }

    private static CacheControl cacheControl(boolean fullArchive) {
        return fullArchive ? REVALIDATE_PRIVATE : REVALIDATE;
    }
}
