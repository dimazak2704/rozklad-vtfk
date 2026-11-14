package ua.vtc.rozklad.notification.support;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.ConfirmationDecision;
import ua.vtc.rozklad.notification.core.CoreClient;
import ua.vtc.rozklad.notification.core.CoreDtos.BellSchedule;
import ua.vtc.rozklad.notification.core.CoreDtos.ChangeSummary;
import ua.vtc.rozklad.notification.core.CoreDtos.CurrentUser;
import ua.vtc.rozklad.notification.core.CoreDtos.PublicGroup;
import ua.vtc.rozklad.notification.core.CoreDtos.PublicMeta;
import ua.vtc.rozklad.notification.core.CoreDtos.PublicTeacher;
import ua.vtc.rozklad.notification.core.CoreDtos.Schedule;
import ua.vtc.rozklad.notification.core.CoreDtos.ScheduleDay;
import ua.vtc.rozklad.notification.core.CoreDtos.ScheduleOwner;
import ua.vtc.rozklad.notification.core.CoreDtos.SearchHit;
import ua.vtc.rozklad.notification.core.OwnerType;
import ua.vtc.rozklad.notification.core.UpstreamRejectedException;
import ua.vtc.rozklad.notification.core.UpstreamUnavailableException;

/**
 * core stand-in with the fixtures of {@link CoreFixtures}; records commands and can be switched off. Like core, it
 * refuses a visitor's schedule that starts before the Monday of the public history window ({@code historyDays} back
 * from {@code today}) and serves any date to the bot's service token ({@link #archiveSchedule}).
 */
public class FakeCoreClient implements CoreClient {

    public record Reply(UUID changeId, UUID teacherId, ConfirmationDecision decision) {
    }

    private final List<Reply> replies = new CopyOnWriteArrayList<>();
    private final List<AbsenceReport> absences = new CopyOnWriteArrayList<>();
    private final AtomicInteger scheduleCalls = new AtomicInteger();
    private final AtomicInteger archiveCalls = new AtomicInteger();
    private final AtomicInteger teacherCalls = new AtomicInteger();
    private final AtomicInteger teacherCallsInTransaction = new AtomicInteger();
    private final java.util.Set<LocalDate> holidays = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final List<PublicGroup> addedGroups = new CopyOnWriteArrayList<>();
    private final List<PublicTeacher> addedTeachers = new CopyOnWriteArrayList<>();
    private final java.util.Map<UUID, TeacherStatus> teacherStatuses = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean unavailable;
    private volatile RuntimeException replyFailure;
    private volatile RuntimeException absenceFailure;
    private volatile ChangeStatus noticeStatus;
    private volatile LocalDate today = LocalDate.of(2026, 10, 19);
    private volatile Integer historyDays = 7;

    public void reset() {
        replies.clear();
        absences.clear();
        scheduleCalls.set(0);
        archiveCalls.set(0);
        historyDays = 7;
        teacherCalls.set(0);
        teacherCallsInTransaction.set(0);
        holidays.clear();
        addedGroups.clear();
        addedTeachers.clear();
        teacherStatuses.clear();
        unavailable = false;
        replyFailure = null;
        absenceFailure = null;
        noticeStatus = null;
        today = LocalDate.of(2026, 10, 19);
    }

    /**
     * Answers as core does for a NOTIFY change in {@code status}: «Добре» keeps the status, «Не можу» declines a
     * CONFIRMED change and keeps an APPLIED one (an objection). {@code null} switches back to CONFIRM answers.
     */
    public void answerAsNotice(ChangeStatus status) {
        this.noticeStatus = status;
    }

    /** A group the dispatcher created after the fixtures (core's catalog changed). */
    public void addGroup(PublicGroup group) {
        addedGroups.add(group);
    }

    /** A teacher the dispatcher created after the fixtures (core's catalog changed). */
    public void addTeacher(PublicTeacher teacher) {
        addedTeachers.add(teacher);
    }

    /** The dispatcher made the teacher inactive ({@code INACTIVE}) or removed him ({@code GONE}). */
    public void setTeacherStatus(UUID teacherId, TeacherStatus status) {
        teacherStatuses.put(teacherId, status);
    }

    public void setUnavailable(boolean value) {
        this.unavailable = value;
    }

    public void failReplies(RuntimeException failure) {
        this.replyFailure = failure;
    }

    public void failAbsences(RuntimeException failure) {
        this.absenceFailure = failure;
    }

    public void setToday(LocalDate value) {
        this.today = value;
    }

    /** The setting {@code public.changesHistoryDays}; {@code null} plays an older core without the window. */
    public void setHistoryDays(Integer days) {
        this.historyDays = days;
    }

    /** Schedules the bot asked for with its service token (the whole archive). */
    public int archiveCalls() {
        return archiveCalls.get();
    }

    public List<Reply> replies() {
        return List.copyOf(replies);
    }

    public List<AbsenceReport> absences() {
        return List.copyOf(absences);
    }

    public int scheduleCalls() {
        return scheduleCalls.get();
    }

    /** Calls of {@link #teachers()}, the HTTP request behind every teacher name. */
    public int teacherCalls() {
        return teacherCalls.get();
    }

    /** Calls of {@link #teachers()} made while the calling thread had a database transaction open. */
    public int teacherCallsInTransaction() {
        return teacherCallsInTransaction.get();
    }

    @Override
    public PublicMeta meta() {
        available();
        return CoreFixtures.meta(today, publicHistoryFrom());
    }

    @Override
    public List<PublicGroup> groups() {
        available();
        return java.util.stream.Stream.concat(CoreFixtures.groups().stream(), addedGroups.stream()).toList();
    }

    @Override
    public List<PublicTeacher> teachers() {
        teacherCalls.incrementAndGet();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            teacherCallsInTransaction.incrementAndGet();
        }
        available();
        // як core: у публічному списку лише активні викладачі
        return java.util.stream.Stream.concat(CoreFixtures.teachers().stream(), addedTeachers.stream())
                .filter(teacher -> teacherStatuses.getOrDefault(teacher.id(), TeacherStatus.ACTIVE)
                        == TeacherStatus.ACTIVE)
                .toList();
    }

    @Override
    public TeacherStatus teacherStatus(UUID teacherId) {
        available();
        return teacherStatuses.getOrDefault(teacherId, TeacherStatus.ACTIVE);
    }

    @Override
    public List<SearchHit> search(String query, int limit) {
        available();
        String normalized = query.strip().toLowerCase(Locale.forLanguageTag("uk"));
        List<SearchHit> hits = switch (normalized) {
            case "4кі1", "4ki1" -> List.of(group("4КІ1", CoreFixtures.GROUP_4KI1));
            case "4кі" -> List.of(group("4КІ1", CoreFixtures.GROUP_4KI1), group("4КІ2", CoreFixtures.GROUP_4KI2));
            case "кренц", "кренцін" -> List.of(new SearchHit("TEACHER", CoreFixtures.TEACHER_KRENTSIN, "Кренцін М.Д.",
                    "Циклова комісія КІ", "/t/" + CoreFixtures.TEACHER_KRENTSIN));
            case "6" -> List.of(new SearchHit("ROOM", CoreFixtures.ROOM_6, "ауд. 6", "Комп’ютерний клас", "/r/6"));
            default -> List.of();
        };
        return hits.stream().limit(limit).toList();
    }

    @Override
    public BellSchedule bells(LocalDate date) {
        available();
        return CoreFixtures.bells(date.getDayOfWeek().getValue());
    }

    /** Holidays (no lessons, not a study day) for every owner. */
    public void addHoliday(LocalDate date) {
        holidays.add(date);
    }

    /** Every date of the range; Monday–Friday outside {@link #addHoliday holidays} are study days. */
    @Override
    public Schedule schedule(OwnerType type, UUID ownerId, LocalDate from, LocalDate to) {
        available();
        LocalDate publicFrom = publicHistoryFrom();
        if (publicFrom != null && from.isBefore(publicFrom.with(java.time.DayOfWeek.MONDAY))) {
            throw new UpstreamRejectedException(404, "Розклад із замінами за тижні до … бачить лише персонал коледжу.",
                    UpstreamRejectedException.HISTORY_NOT_PUBLIC);
        }
        return scheduleOf(type, ownerId, from, to);
    }

    @Override
    public Schedule archiveSchedule(OwnerType type, UUID ownerId, LocalDate from, LocalDate to) {
        available();
        archiveCalls.incrementAndGet();
        return scheduleOf(type, ownerId, from, to);
    }

    private LocalDate publicHistoryFrom() {
        Integer days = historyDays;
        return days == null ? null : today.minusDays(days);
    }

    private Schedule scheduleOf(OwnerType type, UUID ownerId, LocalDate from, LocalDate to) {
        scheduleCalls.incrementAndGet();
        List<ScheduleDay> days = new java.util.ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            days.add(day(type, ownerId, date));
        }
        String label = switch (type) {
            case GROUP -> CoreFixtures.groups().stream().filter(group -> group.id().equals(ownerId))
                    .map(PublicGroup::code).findFirst().orElse("?");
            case TEACHER -> CoreFixtures.teachers().stream().filter(teacher -> teacher.id().equals(ownerId))
                    .map(PublicTeacher::label).findFirst().orElse("?");
            case ROOM -> "ауд. 6";
        };
        return new Schedule(new ScheduleOwner(type.name(), ownerId, label), from, to, days);
    }

    private ScheduleDay day(OwnerType type, UUID ownerId, LocalDate date) {
        boolean studyDay = date.getDayOfWeek().getValue() <= 5 && !holidays.contains(date);
        if (studyDay && type == OwnerType.GROUP && ownerId.equals(CoreFixtures.GROUP_4KI1)) {
            return CoreFixtures.day4ki1(date);
        }
        if (studyDay && type == OwnerType.TEACHER && ownerId.equals(CoreFixtures.TEACHER_NEPYIVODA)) {
            return CoreFixtures.teacherDay(date);
        }
        return new ScheduleDay(date, date.getDayOfWeek().getValue(), null, null, studyDay, null, List.of());
    }

    @Override
    public CurrentUser currentUser(String bearerToken) {
        available();
        return switch (bearerToken) {
            case "teacher-token" -> new CurrentUser(UUID.randomUUID(), "nepyivoda", List.of("TEACHER"),
                    CoreFixtures.teachers().get(1));
            default -> new CurrentUser(UUID.randomUUID(), "someone", List.of("TEACHER"), null);
        };
    }

    @Override
    public ChangeSummary replyToChange(UUID changeId, UUID teacherId, ConfirmationDecision decision, String comment) {
        available();
        if (replyFailure != null) {
            throw replyFailure;
        }
        replies.add(new Reply(changeId, teacherId, decision));
        ChangeStatus notice = noticeStatus;
        if (notice != null) {
            ChangeStatus after = decision == ConfirmationDecision.DECLINE && notice == ChangeStatus.CONFIRMED
                    ? ChangeStatus.DECLINED : notice;
            return new ChangeSummary(changeId, after, "NOTIFY", decision);
        }
        return new ChangeSummary(changeId, decision == ConfirmationDecision.CONFIRM
                ? ChangeStatus.CONFIRMED
                : ChangeStatus.DECLINED, "CONFIRM", decision);
    }

    @Override
    public void reportAbsence(AbsenceReport report) {
        available();
        if (absenceFailure != null) {
            throw absenceFailure;
        }
        absences.add(report);
    }

    private void available() {
        if (unavailable) {
            throw new UpstreamUnavailableException("core", "test: core is down", null);
        }
    }

    private static SearchHit group(String code, UUID id) {
        return new SearchHit("GROUP", id, code, "Комп’ютерна інженерія", "/g/" + code);
    }
}
