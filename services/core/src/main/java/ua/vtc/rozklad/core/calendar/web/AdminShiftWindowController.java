package ua.vtc.rozklad.core.calendar.web;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.ShiftWindowDto;
import ua.vtc.rozklad.core.calendar.internal.ShiftWindowService;

/** {@code GET/PUT /api/admin/shift-windows}: the whole table as a JSON array of 10 rows. */
@RestController
@RequestMapping("/api/admin/shift-windows")
class AdminShiftWindowController {

    private final ShiftWindowService windows;

    AdminShiftWindowController(ShiftWindowService windows) {
        this.windows = windows;
    }

    @GetMapping
    List<ShiftWindowDto> list() {
        return windows.list();
    }

    @PutMapping
    List<ShiftWindowDto> replace(@RequestBody List<ShiftWindowDto> request) {
        return windows.replace(request);
    }
}
