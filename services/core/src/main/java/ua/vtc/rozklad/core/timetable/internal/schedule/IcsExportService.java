package ua.vtc.rozklad.core.timetable.internal.schedule;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.calendar.BellTimes;
import ua.vtc.rozklad.core.calendar.CalendarQueries;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.calendar.SemesterRepository;
import ua.vtc.rozklad.core.calendar.StudyCalendar;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.time.TimeConfig;
import ua.vtc.rozklad.core.timetable.LessonView;
import ua.vtc.rozklad.core.timetable.LessonViewKind;
import ua.vtc.rozklad.core.timetable.ScheduleOwnerType;

/**
 * Calendar subscription (ICS) of a group or teacher for the current semester: every scheduled
 * dated lesson, changes included («Додати в Google Календар», UI.md §4).
 */
@Service
@Transactional(readOnly = true)
public class IcsExportService {

    private static final Logger log = LoggerFactory.getLogger(IcsExportService.class);
    private static final ZoneId KYIV = TimeConfig.KYIV;

    private final ScheduleReadRepository reads;
    private final ScheduleOwners owners;
    private final SemesterRepository semesters;
    private final CalendarQueries calendar;
    private final Clock clock;

    IcsExportService(ScheduleReadRepository reads, ScheduleOwners owners, SemesterRepository semesters,
                     CalendarQueries calendar, Clock clock) {
        this.reads = reads;
        this.owners = owners;
        this.semesters = semesters;
        this.calendar = calendar;
        this.clock = clock;
    }

    /** @throws IllegalArgumentException for room calendars (CONTRACTS.md offers group and teacher only) */
    public IcsFile export(ScheduleOwnerType type, UUID ownerId) {
        if (type == ScheduleOwnerType.ROOM) {
            throw new IllegalArgumentException("ICS is available for groups and teachers only");
        }
        ScheduleOwners.Resolved owner = owners.resolve(type, ownerId);
        Semester semester = currentSemester();
        StudyCalendar semesterCalendar = calendar.studyCalendar(semester.getId());
        BellTimes bells = calendar.bellTimes(semesterCalendar).getOrDefault(semester.getId(), BellTimes.none());
        List<IcsCalendarWriter.Event> events = new ArrayList<>();
        int withoutTimes = 0;
        for (LessonRow row : reads.forOwner(type, ownerId, semester.getStartsOn(), semester.getEndsOn())) {
            if (!row.scheduled()) {
                continue;
            }
            LessonView view = LessonViewAssembler.view(row, bells);
            if (view.startsAt() == null || view.endsAt() == null) {
                withoutTimes++;
                continue;
            }
            events.add(event(row, view, type));
        }
        if (withoutTimes > 0) {
            log.warn("ICS of {} {}: {} lessons skipped because the bell profile has no times for them", type,
                    ownerId, withoutTimes);
        }
        String label = owner.owner().label();
        return new IcsFile("rozklad-" + ownerId + ".ics",
                IcsCalendarWriter.write("Розклад " + label + " — ВТФК", events));
    }

    private Semester currentSemester() {
        LocalDate today = LocalDate.now(clock);
        return semesters.findByActiveTrue()
                .or(() -> semesters.findContaining(today))
                .orElseThrow(() -> new NotFoundException("Немає активного семестру: календар поки недоступний"));
    }

    private static IcsCalendarWriter.Event event(LessonRow row, LessonView view, ScheduleOwnerType type) {
        String subgroup = view.subgroup() > 0 ? " (підгр. " + view.subgroup() + ")" : "";
        String counterpart = type == ScheduleOwnerType.GROUP
                ? (view.teacher() == null ? null : view.teacher().shortName())
                : view.group().code();
        String summary = view.discipline().shortName() + subgroup + (counterpart == null ? "" : " — " + counterpart);
        List<String> details = new ArrayList<>();
        details.add(view.discipline().name());
        details.add("Група: " + view.group().code() + subgroup);
        if (view.teacher() != null) {
            details.add("Викладач: " + view.teacher().shortName());
        }
        if (view.kind() != LessonViewKind.REGULAR) {
            details.add(kindText(view));
        }
        if (view.online()) {
            details.add("Дистанційно");
        }
        String location = view.room() == null ? (view.online() ? "Дистанційно" : null) : "ауд. " + view.room().name();
        return new IcsCalendarWriter.Event(row.id() + "@rozklad.vtfk",
                row.date().atTime(view.startsAt()).atZone(KYIV).toInstant(),
                row.date().atTime(view.endsAt()).atZone(KYIV).toInstant(),
                row.updatedAt(), summary, location, String.join("\n", details));
    }

    private static String kindText(LessonView view) {
        String reason = view.reasonText() == null ? "" : " (" + view.reasonText() + ")";
        return switch (view.kind()) {
            case SUBSTITUTED -> "Заміна" + reason;
            case MOVED -> "Перенесено" + reason;
            case ROOM_CHANGED -> "Інша аудиторія" + reason;
            case ADDITIONAL -> "Додаткова пара" + reason;
            case BLOCK -> "Блок пар" + reason;
            case CANCELLED, REGULAR -> "";
        };
    }

    /** {@code content} is the iCalendar text (UTF-8, CRLF). */
    public record IcsFile(String fileName, String content) {
    }
}
