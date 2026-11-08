package ua.vtc.rozklad.core.calendar.web;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.BellsDto;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.PublicMetaDto;
import ua.vtc.rozklad.core.calendar.internal.PublicCalendarService;

/** Anonymous {@code GET /api/public/meta} and {@code GET /api/public/bells?date=}. */
@RestController
@RequestMapping("/api/public")
class PublicCalendarController {

    private final PublicCalendarService calendar;

    PublicCalendarController(PublicCalendarService calendar) {
        this.calendar = calendar;
    }

    @GetMapping("/meta")
    PublicMetaDto meta() {
        return calendar.meta();
    }

    @GetMapping("/bells")
    BellsDto bells(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return calendar.bells(date);
    }
}
