package ua.vtc.rozklad.core.calendar.internal;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.calendar.ShiftWindow;
import ua.vtc.rozklad.core.calendar.ShiftWindowRepository;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.ShiftWindowDto;
import ua.vtc.rozklad.core.shared.error.FieldErrors;

/**
 * Вікна змін: which pairs each shift may use on each weekday (shift 1 → 1–4; shift 2 → 5–8
 * Mon–Thu and 4–6 Fri). PUT replaces the whole table, which must cover both shifts on Mon–Fri.
 */
@Service
@Transactional(readOnly = true)
public class ShiftWindowService {

    private final ShiftWindowRepository windows;

    public ShiftWindowService(ShiftWindowRepository windows) {
        this.windows = windows;
    }

    public List<ShiftWindowDto> list() {
        return windows.findAllByOrderByShiftAscWeekdayAsc().stream()
                .map(window -> new ShiftWindowDto(window.getShift(), window.getWeekday(), window.getFirstPair(),
                        window.getLastPair()))
                .toList();
    }

    @Transactional
    public List<ShiftWindowDto> replace(List<ShiftWindowDto> requested) {
        validate(requested);
        for (ShiftWindowDto window : requested) {
            windows.findByShiftAndWeekday(window.shift(), window.weekday()).ifPresentOrElse(
                    existing -> existing.setPairs(window.firstPair(), window.lastPair()),
                    () -> windows.save(new ShiftWindow(window.shift(), window.weekday(), window.firstPair(),
                            window.lastPair())));
        }
        windows.flush();
        return list();
    }

    private static void validate(List<ShiftWindowDto> requested) {
        FieldErrors errors = new FieldErrors();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < requested.size(); i++) {
            ShiftWindowDto window = requested.get(i);
            String field = "[%d]".formatted(i);
            if (window == null) {
                errors.add(field, "Рядок не може бути порожнім.");
                continue;
            }
            boolean complete = inRange(window.shift(), 1, 2, field + ".shift", "Зміна — 1 або 2.", errors)
                    & inRange(window.weekday(), 1, 5, field + ".weekday", "День — від 1 (пн) до 5 (пт).", errors)
                    & inRange(window.firstPair(), 1, 8, field + ".firstPair", "Пара — від 1 до 8.", errors)
                    & inRange(window.lastPair(), 1, 8, field + ".lastPair", "Пара — від 1 до 8.", errors);
            if (!complete) {
                continue;
            }
            if (window.lastPair() < window.firstPair()) {
                errors.add(field + ".lastPair", "Остання пара не може бути раніше за першу.");
            }
            if (!seen.add(window.shift() + ":" + window.weekday())) {
                errors.add(field + ".weekday", "Зміна %d на цей день уже задана.".formatted(window.shift()));
            }
        }
        if (errors.isEmpty() && (seen.size() != 10 || requested.size() != 10)) {
            errors.add("windows", "Потрібні вікна для обох змін на кожен день з понеділка по п'ятницю (10 рядків).");
        }
        errors.throwIfAny();
    }

    private static boolean inRange(Integer value, int min, int max, String field, String message, FieldErrors errors) {
        if (value == null || value < min || value > max) {
            errors.add(field, message);
            return false;
        }
        return true;
    }
}
