package ua.vtc.rozklad.core.timetable.internal.calendar;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.calendar.ShiftWindowRepository;

/** Reads the shift windows; logs once when the reference data is missing and the DOMAIN rule is used. */
@Component
public class ShiftWindowsLoader {

    private static final Logger log = LoggerFactory.getLogger(ShiftWindowsLoader.class);

    private final ShiftWindowRepository repository;
    private final AtomicBoolean defaultsReported = new AtomicBoolean();

    public ShiftWindowsLoader(ShiftWindowRepository repository) {
        this.repository = repository;
    }

    public ShiftWindows load() {
        ShiftWindows windows = ShiftWindows.of(repository.findAllByOrderByShiftAscWeekdayAsc());
        if (windows.fromDomainDefaults() && defaultsReported.compareAndSet(false, true)) {
            log.warn("Table shift_window is empty: using DOMAIN §2 windows (shift 1: 1-4; shift 2: 5-8 Mon-Thu, 4-6 Fri)");
        }
        return windows;
    }
}
