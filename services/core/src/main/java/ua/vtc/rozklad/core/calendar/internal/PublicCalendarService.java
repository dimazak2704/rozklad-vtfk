package ua.vtc.rozklad.core.calendar.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.calendar.BellProfileRepository;
import ua.vtc.rozklad.core.calendar.CalendarQueries;
import ua.vtc.rozklad.core.calendar.CalendarQueries.BellProfileInfo;
import ua.vtc.rozklad.core.calendar.DayInfo;
import ua.vtc.rozklad.core.calendar.SemesterInfo;
import ua.vtc.rozklad.core.calendar.StudyCalendar;
import ua.vtc.rozklad.core.calendar.WeekInfo;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.BellsDto;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.PairDto;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.ProfileRefDto;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.PublicMetaDto;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.SemesterRefDto;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.SlotDto;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.StudyDayDto;
import ua.vtc.rozklad.core.settings.SettingsService;
import ua.vtc.rozklad.core.shared.error.NotFoundException;

/**
 * {@code /api/public/meta} (header of every public page: semester, week, parity, current pair)
 * and {@code /api/public/bells}. "Today" and "now" come from the Kyiv {@link Clock}.
 */
@Service
@Transactional(readOnly = true)
public class PublicCalendarService {

    /** How far {@code nextStudyDay} looks ahead: covers the summer break between two semesters. */
    static final int NEXT_STUDY_DAY_HORIZON_DAYS = 120;

    private final CalendarQueries calendar;
    private final BellProfileRepository bellProfiles;
    private final SettingsService settings;
    private final Clock clock;

    public PublicCalendarService(CalendarQueries calendar, BellProfileRepository bellProfiles,
                                 SettingsService settings, Clock clock) {
        this.calendar = calendar;
        this.bellProfiles = bellProfiles;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * The semester of today, otherwise the active one (between semesters the header still names
     * the semester; week and parity are then {@code null} because today is outside it).
     */
    public PublicMetaDto meta() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock);
        Optional<SemesterInfo> semester = calendar.semesterOn(today).or(calendar::activeSemester);
        Optional<WeekInfo> week = semester.filter(found -> found.contains(today)).flatMap(found -> calendar.week(today));
        boolean studyDay = calendar.isStudyDay(today);
        PairDto current = calendar.currentPair(now)
                .map(pair -> new PairDto(pair.pair(), pair.startsAt(), pair.endsAt(), pair.minutesLeft()))
                .orElse(null);
        PairDto next = calendar.nextPair(now)
                .map(pair -> new PairDto(pair.pair(), pair.startsAt(), pair.endsAt(), null))
                .orElse(null);
        return new PublicMetaDto(
                settings.collegeName(),
                semester.map(found -> new SemesterRefDto(found.id(), found.name(), found.startsOn(), found.endsOn()))
                        .orElse(null),
                today,
                week.map(WeekInfo::weekNumber).orElse(null),
                week.map(WeekInfo::parity).orElse(null),
                studyDay,
                current,
                next,
                calendar.bellProfileOn(today).map(PublicCalendarService::profileRef).orElse(null),
                nextStudyDay(today));
    }

    /**
     * First study day after {@code today} for the whole college (weekends, holidays and days between
     * semesters skipped), with its week number and parity; one batch read of the calendar.
     */
    private StudyDayDto nextStudyDay(LocalDate today) {
        LocalDate from = today.plusDays(1);
        LocalDate to = today.plusDays(NEXT_STUDY_DAY_HORIZON_DAYS);
        StudyCalendar study = calendar.studyCalendar(from, to);
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            DayInfo day = study.day(date, null);
            if (day.studyDay() && day.parity() != null) {
                return new StudyDayDto(date, day.weekday(), day.weekNumber(), day.parity());
            }
        }
        return null;
    }

    /**
     * Pair times of a date from the profile in effect; without a date — today plus the list of
     * all active profiles.
     *
     * @throws NotFoundException when no bell profile is configured (neither the semester nor settings)
     */
    public BellsDto bells(LocalDate requestedDate) {
        LocalDate date = requestedDate != null ? requestedDate : LocalDate.now(clock);
        BellProfileInfo profile = calendar.bellProfileOn(date)
                .orElseThrow(() -> new NotFoundException("Розклад дзвінків не налаштовано: виберіть профіль дзвінків "
                        + "для семестру або типовий у налаштуваннях."));
        List<SlotDto> slots = calendar.bellSlots(date).stream()
                .map(slot -> new SlotDto(slot.pair(), slot.startsAt(), slot.endsAt()))
                .toList();
        Optional<UUID> defaultId = settings.activeBellProfileId();
        var profiles = requestedDate != null ? null : bellProfiles.findAllByOrderByName().stream()
                .filter(found -> found.isActive())
                .map(found -> BellProfileService.toDto(found, defaultId))
                .toList();
        return new BellsDto(profileRef(profile), date, date.getDayOfWeek().getValue(), calendar.isStudyDay(date),
                slots, profiles);
    }

    private static ProfileRefDto profileRef(BellProfileInfo profile) {
        return new ProfileRefDto(profile.id(), profile.name());
    }
}
