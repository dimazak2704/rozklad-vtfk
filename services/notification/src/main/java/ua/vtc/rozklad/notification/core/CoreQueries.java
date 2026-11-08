package ua.vtc.rozklad.notification.core;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import ua.vtc.rozklad.notification.core.CoreDtos.BellSchedule;
import ua.vtc.rozklad.notification.core.CoreDtos.PublicGroup;
import ua.vtc.rozklad.notification.core.CoreDtos.PublicMeta;
import ua.vtc.rozklad.notification.core.CoreDtos.PublicTeacher;
import ua.vtc.rozklad.notification.core.CoreDtos.Schedule;
import ua.vtc.rozklad.notification.core.CoreDtos.ScheduleDay;
import ua.vtc.rozklad.notification.core.CoreDtos.SearchHit;

/**
 * Cached reads of core's public API for the bot. Schedules are cached per day under a
 * "generation" number that {@code schedule.changed} bumps, so one event invalidates all of them.
 */
@Service
public class CoreQueries {

    static final Duration META_TTL = Duration.ofSeconds(30);
    static final Duration CATALOG_TTL = Duration.ofHours(1);
    static final Duration BELLS_TTL = Duration.ofHours(6);
    static final Duration SCHEDULE_TTL = Duration.ofMinutes(10);
    static final Duration SEARCH_TTL = Duration.ofMinutes(10);
    static final String SCHEDULE_GENERATION = "schedule-generation";
    static final int SEARCH_LIMIT = 8;
    /** core serves at most 31 days per schedule request. */
    static final int NEXT_STUDY_DAY_LOOKAHEAD_DAYS = 30;

    private final CoreClient core;
    private final RedisJsonCache cache;

    public CoreQueries(CoreClient core, RedisJsonCache cache) {
        this.core = core;
        this.cache = cache;
    }

    public PublicMeta meta() {
        return cache.get("meta", new TypeReference<>() {
        }, META_TTL, core::meta);
    }

    /** Active groups sorted by course and code. */
    public List<PublicGroup> groups() {
        List<PublicGroup> groups = cache.get("groups", new TypeReference<List<PublicGroup>>() {
        }, CATALOG_TTL, core::groups);
        return groups.stream()
                .sorted(Comparator.comparingInt(PublicGroup::course).thenComparing(PublicGroup::code))
                .toList();
    }

    public Optional<PublicGroup> group(UUID id) {
        return groups().stream().filter(group -> group.id().equals(id)).findFirst();
    }

    public List<PublicTeacher> teachers() {
        return cache.get("teachers", new TypeReference<List<PublicTeacher>>() {
        }, CATALOG_TTL, core::teachers);
    }

    public Optional<PublicTeacher> teacher(UUID id) {
        return teachers().stream().filter(teacher -> teacher.id().equals(id)).findFirst();
    }

    public List<SearchHit> search(String query) {
        String normalized = query.strip().toLowerCase(Locale.ROOT);
        return cache.get("search:" + normalized, new TypeReference<List<SearchHit>>() {
        }, SEARCH_TTL, () -> core.search(query.strip(), SEARCH_LIMIT));
    }

    public BellSchedule bells(LocalDate date) {
        return cache.get("bells:" + date, new TypeReference<>() {
        }, BELLS_TTL, () -> core.bells(date));
    }

    /**
     * Lessons of one owner on one date. When core returns no day for the date (weekend, outside the
     * semester) the result is an empty, non-study day.
     */
    public DaySchedule day(OwnerType type, UUID ownerId, LocalDate date) {
        long generation = cache.counter(SCHEDULE_GENERATION);
        String key = "schedule:" + generation + ":" + type.code() + ":" + ownerId + ":" + date;
        Schedule schedule = cache.get(key, new TypeReference<>() {
        }, SCHEDULE_TTL, () -> core.schedule(type, ownerId, date, date));
        ScheduleDay day = schedule.days().stream()
                .filter(candidate -> date.equals(candidate.date()))
                .findFirst()
                .orElseGet(() -> new ScheduleDay(date, date.getDayOfWeek().getValue(), null, null, false, null,
                        List.of()));
        String label = schedule.owner() != null && schedule.owner().label() != null
                ? schedule.owner().label()
                : "";
        return new DaySchedule(type, ownerId, label, day);
    }

    /**
     * The first study day of the owner after {@code date} within {@value #NEXT_STUDY_DAY_LOOKAHEAD_DAYS}
     * days (core's {@code studyDay}: weekends, holidays and the owner's practice skipped); one schedule
     * read for the whole range.
     */
    public Optional<LocalDate> nextStudyDay(OwnerType type, UUID ownerId, LocalDate date) {
        LocalDate from = date.plusDays(1);
        LocalDate to = date.plusDays(NEXT_STUDY_DAY_LOOKAHEAD_DAYS);
        long generation = cache.counter(SCHEDULE_GENERATION);
        String key = "schedule:" + generation + ":" + type.code() + ":" + ownerId + ":" + from + ".." + to;
        Schedule schedule = cache.get(key, new TypeReference<>() {
        }, SCHEDULE_TTL, () -> core.schedule(type, ownerId, from, to));
        return schedule.days().stream()
                .filter(day -> day.studyDay() && day.date() != null && day.date().isAfter(date))
                .map(ScheduleDay::date)
                .min(Comparator.naturalOrder());
    }

    /** {@code schedule.changed}: every cached schedule becomes stale at once. */
    public void invalidateSchedules() {
        cache.increment(SCHEDULE_GENERATION);
        cache.evict("meta");
    }

    /** One day of one owner (group, teacher or room) with the owner's display label. */
    public record DaySchedule(OwnerType ownerType, UUID ownerId, String ownerLabel, ScheduleDay day) {
    }
}
