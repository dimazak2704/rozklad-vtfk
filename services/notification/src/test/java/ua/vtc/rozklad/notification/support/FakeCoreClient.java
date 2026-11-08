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
import ua.vtc.rozklad.notification.core.UpstreamUnavailableException;

/** core stand-in with the fixtures of {@link CoreFixtures}; records commands and can be switched off. */
public class FakeCoreClient implements CoreClient {

    public record Reply(UUID changeId, UUID teacherId, ConfirmationDecision decision) {
    }

    private final List<Reply> replies = new CopyOnWriteArrayList<>();
    private final List<AbsenceReport> absences = new CopyOnWriteArrayList<>();
    private final AtomicInteger scheduleCalls = new AtomicInteger();
    private final AtomicInteger teacherCalls = new AtomicInteger();
    private final AtomicInteger teacherCallsInTransaction = new AtomicInteger();
    private final java.util.Set<LocalDate> holidays = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean unavailable;
    private volatile RuntimeException replyFailure;
    private volatile RuntimeException absenceFailure;
    private volatile LocalDate today = LocalDate.of(2026, 10, 19);

    public void reset() {
        replies.clear();
        absences.clear();
        scheduleCalls.set(0);
        teacherCalls.set(0);
        teacherCallsInTransaction.set(0);
        holidays.clear();
        unavailable = false;
        replyFailure = null;
        absenceFailure = null;
        today = LocalDate.of(2026, 10, 19);
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
        return CoreFixtures.meta(today);
    }

    @Override
    public List<PublicGroup> groups() {
        available();
        return CoreFixtures.groups();
    }

    @Override
    public List<PublicTeacher> teachers() {
        teacherCalls.incrementAndGet();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            teacherCallsInTransaction.incrementAndGet();
        }
        available();
        return CoreFixtures.teachers();
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
        return new ChangeSummary(changeId, decision == ConfirmationDecision.CONFIRM
                ? ChangeStatus.CONFIRMED
                : ChangeStatus.DECLINED);
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
