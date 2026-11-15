package ua.vtc.rozklad.core.calendar.internal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import ua.vtc.rozklad.core.calendar.AcademicYearRepository;
import ua.vtc.rozklad.core.calendar.CalendarEdited;
import ua.vtc.rozklad.core.calendar.CalendarPeriod;
import ua.vtc.rozklad.core.calendar.CalendarPeriodRepository;
import ua.vtc.rozklad.core.calendar.PeriodEffect;
import ua.vtc.rozklad.core.calendar.PeriodKind;
import ua.vtc.rozklad.core.calendar.PeriodKindRepository;
import ua.vtc.rozklad.core.calendar.PeriodScope;
import ua.vtc.rozklad.core.calendar.PeriodSource;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.calendar.SemesterRepository;
import ua.vtc.rozklad.core.calendar.StudyCalendar;
import ua.vtc.rozklad.core.calendar.StudyMode;
import ua.vtc.rozklad.core.calendar.internal.CalendarChangePublisher.PeriodSnapshot;
import ua.vtc.rozklad.core.calendar.internal.CalendarPeriodImportDtos.CalendarPeriodImportRequest;
import ua.vtc.rozklad.core.calendar.internal.CalendarPeriodImportDtos.CalendarPeriodImportResult;
import ua.vtc.rozklad.core.calendar.internal.CalendarPeriodImportDtos.GroupSemesterCounts;
import ua.vtc.rozklad.core.calendar.internal.CalendarPeriodImportDtos.ImportAction;
import ua.vtc.rozklad.core.calendar.internal.CalendarPeriodImportDtos.ImportGroupSemester;
import ua.vtc.rozklad.core.calendar.internal.CalendarPeriodImportDtos.ImportPeriod;
import ua.vtc.rozklad.core.calendar.internal.CalendarPeriodImportDtos.KeptPart;
import ua.vtc.rozklad.core.calendar.internal.CalendarPeriodImportDtos.ManualOverlap;
import ua.vtc.rozklad.core.calendar.internal.CalendarPeriodImportDtos.ManualRow;
import ua.vtc.rozklad.core.calendar.internal.CalendarPeriodImportDtos.OutOfSemester;
import ua.vtc.rozklad.core.calendar.internal.CalendarPeriodImportDtos.PeriodRow;
import ua.vtc.rozklad.core.calendar.internal.CalendarPeriodImportDtos.SkippedGroupSemester;
import ua.vtc.rozklad.core.calendar.internal.PeriodKindDtos.LessonCounts;
import ua.vtc.rozklad.core.catalog.CatalogQueries;
import ua.vtc.rozklad.core.catalog.GroupCodes;
import ua.vtc.rozklad.core.catalog.GroupInfo;
import ua.vtc.rozklad.core.catalog.RoomInfo;
import ua.vtc.rozklad.core.shared.error.ConflictDto;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.FieldErrors;

/**
 * {@code POST /api/admin/import/calendar-periods}: the periods and theory weeks of the college's academic
 * calendar in one transaction, idempotent by content.
 * <ul>
 *   <li>Each item is cut to the semesters of its academic year (one part per semester; a part's key is the
 *   item's key, or {@code key|S<n>} when the item spans two semesters). Days outside every semester are
 *   reported, not stored.</li>
 *   <li>Upsert by key: only rows the import wrote ({@code source = IMPORT}) are created, updated or deleted;
 *   a row the dispatcher edited ({@code MANUAL}) is reported with what differs.</li>
 *   <li>One calendar re-check for all written rows: lessons follow from today on, and a clash with a live change
 *   refuses the whole import (409 {@code conflicts[]}) — in a dry run the clashes are listed instead and the
 *   transaction rolls back.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class CalendarPeriodImportService {

    static final String DUPLICATE_KEY = "Ключ повторюється.";
    static final String UNKNOWN_KIND = "Невідомий вид «%s».";
    static final String INACTIVE_KIND = "Вид «%s» вимкнено.";
    static final String UNKNOWN_GROUP = "Групу «%s» не знайдено.";
    static final String NO_SEMESTER = "Семестру %s %s немає в системі.";

    private final CalendarPeriodRepository periods;
    private final PeriodKindRepository kinds;
    private final SemesterRepository semesters;
    private final AcademicYearRepository academicYears;
    private final CatalogQueries catalog;
    private final CalendarChangePublisher changes;
    private final GroupSemesterService groupSemesters;
    private final JdbcTemplate jdbc;

    CalendarPeriodImportService(CalendarPeriodRepository periods, PeriodKindRepository kinds,
                                SemesterRepository semesters, AcademicYearRepository academicYears,
                                CatalogQueries catalog, CalendarChangePublisher changes,
                                GroupSemesterService groupSemesters, JdbcTemplate jdbc) {
        this.periods = periods;
        this.kinds = kinds;
        this.semesters = semesters;
        this.academicYears = academicYears;
        this.catalog = catalog;
        this.changes = changes;
        this.groupSemesters = groupSemesters;
        this.jdbc = jdbc;
    }

    @Transactional
    public CalendarPeriodImportResult apply(CalendarPeriodImportRequest request) {
        return run(request, false);
    }

    /** The same import in a transaction that is rolled back: nothing is saved and no event leaves. */
    @Transactional
    public CalendarPeriodImportResult preview(CalendarPeriodImportRequest request) {
        try {
            return run(request, true);
        } finally {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }

    private CalendarPeriodImportResult run(CalendarPeriodImportRequest request, boolean dryRun) {
        References refs = new References(kinds.findAll(), catalog.allGroups());
        FieldErrors errors = new FieldErrors();
        List<Item> items = items(request.periods(), refs, errors);
        List<GroupWeeks> weeks = groupWeeks(request.groupSemesters(), refs, errors);
        errors.throwIfAny();

        // повтор імпортера чи два імпорти одного року одночасно чекають один на одного
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", "calendar-import:" + request.academicYear());
        List<Semester> yearSemesters = academicYears.findByName(request.academicYear())
                .map(year -> semesters.findAllByAcademicYearIdOrderByNumber(year.getId()))
                .orElse(List.of());

        Map<String, Part> desired = new LinkedHashMap<>();
        List<OutOfSemester> outOfSemester = new ArrayList<>();
        for (Item item : items) {
            List<Part> parts = split(item, yearSemesters);
            parts.forEach(part -> desired.put(part.storedKey(), part));
            if (hasWeekdayOutside(item, yearSemesters)) {
                outOfSemester.add(new OutOfSemester(item.sourceKey(), item.kind().getCode(), item.startsOn(),
                        item.endsOn(), item.groupCodes(), parts.stream()
                        .map(part -> new KeptPart(part.storedKey(), part.semester().getId(), part.startsOn(),
                                part.endsOn()))
                        .toList()));
            }
        }

        List<UUID> semesterIds = yearSemesters.stream().map(Semester::getId).toList();
        List<CalendarPeriod> existing = semesterIds.isEmpty() ? List.of() : periods.findAllInSemesters(semesterIds);
        Map<String, CalendarPeriod> byKey = existing.stream().filter(period -> period.getSourceKey() != null)
                .collect(Collectors.toMap(CalendarPeriod::getSourceKey, period -> period, (a, b) -> a));
        Map<UUID, Set<UUID>> groupsInScope = new HashMap<>();

        List<Planned> planned = new ArrayList<>();
        List<ManualRow> manual = new ArrayList<>();
        for (Part part : desired.values()) {
            CalendarPeriod stored = byKey.get(part.storedKey());
            if (stored == null) {
                planned.add(new Planned(part, null, ImportAction.CREATE));
            } else if (stored.getSource() == PeriodSource.MANUAL) {
                planned.add(new Planned(part, stored, ImportAction.SKIP_MANUAL));
                manual.add(new ManualRow(part.storedKey(), stored.getId(), stored.getTitle(), differences(stored, part)));
            } else {
                planned.add(new Planned(part, stored, differences(stored, part).isEmpty()
                        ? ImportAction.UNCHANGED : ImportAction.UPDATE));
            }
        }
        existing.stream()
                .filter(period -> period.getSource() == PeriodSource.IMPORT && period.getSourceKey() != null
                        && !desired.containsKey(period.getSourceKey()))
                .sorted(Comparator.comparing(CalendarPeriod::getStartsOn).thenComparing(CalendarPeriod::getSourceKey))
                .forEach(period -> planned.add(new Planned(null, period, ImportAction.DELETE)));
        List<ManualOverlap> overlaps = manualOverlaps(existing, desired, refs, groupsInScope);

        boolean writes = planned.stream().anyMatch(row -> row.action() == ImportAction.CREATE
                || row.action() == ImportAction.UPDATE || row.action() == ImportAction.DELETE);
        LocalDate from = yearSemesters.stream().map(Semester::getStartsOn).min(Comparator.naturalOrder()).orElse(null);
        LocalDate to = yearSemesters.stream().map(Semester::getEndsOn).max(Comparator.naturalOrder()).orElse(null);
        // календар «до» читаємо раніше, ніж імпорт торкнеться сутностей: запит спершу скинув би правки в базу
        StudyCalendar before = writes ? changes.before(from, to) : null;

        List<PeriodSnapshot> snapshots = new ArrayList<>();
        List<PeriodRow> rows = new ArrayList<>();
        for (Planned row : planned) {
            rows.add(write(row, snapshots, refs, dryRun));
        }
        periods.flush();
        GroupSemesterCounts weekCounts = writeGroupWeeks(weeks, yearSemesters, request.academicYear());

        CalendarEdited.Outcome outcome = new CalendarEdited.Outcome();
        List<ConflictDto> conflicts = List.of();
        if (writes) {
            try {
                // числа заповнено ще до відмови: пробний прогін показує їх і для правки, яку буде відхилено
                changes.periodsChanged(snapshots, from, to, before, outcome);
            } catch (ConflictException clash) {
                if (!dryRun) {
                    throw clash;
                }
                conflicts = clash.getConflicts();
            }
        }
        return new CalendarPeriodImportResult(dryRun, request.academicYear(), conflicts.isEmpty(),
                count(planned, ImportAction.CREATE), count(planned, ImportAction.UPDATE),
                count(planned, ImportAction.UNCHANGED), count(planned, ImportAction.DELETE),
                count(planned, ImportAction.SKIP_MANUAL), rows, manual, overlaps, outOfSemester, weekCounts,
                conflicts, new LessonCounts(outcome.added(), outcome.removed(), outcome.updated()));
    }

    /** Resolves every item; field errors {@code periods[i].…} for whatever does not resolve. */
    private List<Item> items(List<ImportPeriod> requested, References refs, FieldErrors errors) {
        List<Item> items = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < requested.size(); i++) {
            ImportPeriod source = requested.get(i);
            String field = "periods[%d]".formatted(i);
            int before = errors.size();
            if (!keys.add(source.sourceKey())) {
                errors.add(field + ".sourceKey", DUPLICATE_KEY);
            }
            PeriodKind kind = refs.kind(source.kindCode()).orElse(null);
            if (kind == null) {
                errors.add(field + ".kindCode", UNKNOWN_KIND.formatted(source.kindCode()));
            } else if (!kind.isActive()) {
                errors.add(field + ".kindCode", INACTIVE_KIND.formatted(source.kindCode()));
            }
            if (source.endsOn().isBefore(source.startsOn())) {
                errors.add(field + ".endsOn", "Кінець періоду не може бути раніше за початок.");
            }
            PeriodScope scope = source.scope() == null ? PeriodScope.ALL : source.scope();
            List<String> codes = source.groupCodes() == null ? List.of() : source.groupCodes();
            List<Integer> courses = source.courses() == null ? List.of() : source.courses();
            Map<UUID, String> groups = new LinkedHashMap<>();
            switch (scope) {
                case ALL -> {
                    if (!codes.isEmpty() || !courses.isEmpty()) {
                        errors.add(field + ".scope", "Для періоду «для всіх» не вказують курси чи групи.");
                    }
                }
                case COURSES -> {
                    if (courses.isEmpty()) {
                        errors.add(field + ".courses", "Вкажіть хоча б один курс.");
                    }
                    if (!codes.isEmpty()) {
                        errors.add(field + ".groupCodes", "Для періоду за курсами групи не вказують.");
                    }
                }
                case GROUPS -> {
                    if (codes.isEmpty()) {
                        errors.add(field + ".groupCodes", "Вкажіть хоча б одну групу.");
                    }
                    if (!courses.isEmpty()) {
                        errors.add(field + ".courses", "Для періоду за групами курси не вказують.");
                    }
                    for (int j = 0; j < codes.size(); j++) {
                        Optional<GroupInfo> group = refs.group(codes.get(j));
                        if (group.isEmpty()) {
                            errors.add("%s.groupCodes[%d]".formatted(field, j), UNKNOWN_GROUP.formatted(codes.get(j)));
                        } else {
                            groups.putIfAbsent(group.get().id(), group.get().code());
                        }
                    }
                }
            }
            UUID roomId = null;
            if (kind != null) {
                PeriodEffect effect = kind.getEffect();
                if (effect == PeriodEffect.ONLINE && source.studyMode() == null) {
                    errors.add(field + ".studyMode", CalendarPeriodService.STUDY_MODE_REQUIRED);
                }
                if (effect != PeriodEffect.ONLINE && source.studyMode() != null) {
                    errors.add(field + ".studyMode", CalendarPeriodService.STUDY_MODE_FORBIDDEN);
                }
                if (source.roomName() != null && !source.roomName().isBlank()) {
                    if (effect != PeriodEffect.SUSPEND) {
                        errors.add(field + ".roomName", CalendarPeriodService.ROOM_FORBIDDEN);
                    } else {
                        Optional<RoomInfo> room = refs.room(source.roomName(), catalog);
                        if (room.isEmpty()) {
                            errors.add(field + ".roomName", CalendarPeriodService.UNKNOWN_ROOM);
                        } else {
                            roomId = room.get().id();
                        }
                    }
                }
            }
            if (errors.size() == before) {
                items.add(new Item(source.sourceKey(), kind, source.title().strip(), source.startsOn(),
                        source.endsOn(), scope, new TreeSet<>(courses), List.copyOf(groups.keySet()),
                        List.copyOf(groups.values()), roomId, source.studyMode(), blankToNull(source.orderNote())));
            }
        }
        return items;
    }

    private List<GroupWeeks> groupWeeks(List<ImportGroupSemester> requested, References refs, FieldErrors errors) {
        if (requested == null) {
            return List.of();
        }
        List<GroupWeeks> weeks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < requested.size(); i++) {
            ImportGroupSemester source = requested.get(i);
            String field = "groupSemesters[%d]".formatted(i);
            Optional<GroupInfo> group = refs.group(source.groupCode());
            if (group.isEmpty()) {
                errors.add(field + ".groupCode", UNKNOWN_GROUP.formatted(source.groupCode()));
                continue;
            }
            if (!seen.add(group.get().id() + "|" + source.semesterNumber())) {
                errors.add(field + ".groupCode", "Групу «%s» для семестру %d вже вказано."
                        .formatted(group.get().code(), source.semesterNumber()));
                continue;
            }
            weeks.add(new GroupWeeks(group.get(), source.semesterNumber(), source.theoryWeeks(), source.exams()));
        }
        return weeks;
    }

    /** One part per semester the item overlaps; the key gets {@code |S<n>} only when there are several. */
    private static List<Part> split(Item item, List<Semester> yearSemesters) {
        List<Semester> overlapping = yearSemesters.stream()
                .filter(semester -> !semester.getStartsOn().isAfter(item.endsOn())
                        && !semester.getEndsOn().isBefore(item.startsOn()))
                .toList();
        return overlapping.stream()
                .map(semester -> new Part(item, semester,
                        overlapping.size() == 1 ? item.sourceKey() : item.sourceKey() + "|S" + semester.getNumber(),
                        max(item.startsOn(), semester.getStartsOn()), min(item.endsOn(), semester.getEndsOn())))
                .toList();
    }

    private static boolean hasWeekdayOutside(Item item, List<Semester> yearSemesters) {
        for (LocalDate date = item.startsOn(); !date.isAfter(item.endsOn()); date = date.plusDays(1)) {
            LocalDate day = date;
            if (PeriodRules.isWeekday(day) && yearSemesters.stream().noneMatch(semester -> semester.contains(day))) {
                return true;
            }
        }
        return false;
    }

    /** Names of the fields where the stored row differs from the part (sorted). */
    private static List<String> differences(CalendarPeriod stored, Part part) {
        Item item = part.item();
        TreeSet<String> differs = new TreeSet<>();
        if (!stored.getKind().getId().equals(item.kind().getId())) {
            differs.add("kindCode");
        }
        if (!stored.getTitle().equals(item.title())) {
            differs.add("title");
        }
        if (!stored.getStartsOn().equals(part.startsOn())) {
            differs.add("startsOn");
        }
        if (!stored.getEndsOn().equals(part.endsOn())) {
            differs.add("endsOn");
        }
        if (stored.getScope() != item.scope()) {
            differs.add("scope");
        }
        if (!new TreeSet<>(stored.getCourses()).equals(item.courses())) {
            differs.add("courses");
        }
        if (!stored.getGroupIds().equals(Set.copyOf(item.groupIds()))) {
            differs.add("groupCodes");
        }
        if (!Objects.equals(stored.getRoomId(), item.roomId())) {
            differs.add("roomName");
        }
        if (stored.getStudyMode() != item.studyMode()) {
            differs.add("studyMode");
        }
        if (!Objects.equals(stored.getOrderNote(), item.orderNote())) {
            differs.add("orderNote");
        }
        return List.copyOf(differs);
    }

    /**
     * Manual periods of the same effect that share a group and dates with a stored part: hand-made ones (no key) and
     * imported ones the dispatcher edited whose key the request no longer produces (the file changed that run's
     * dates or title since). A manual row whose key the request produces is listed in {@code manual} instead.
     */
    private List<ManualOverlap> manualOverlaps(List<CalendarPeriod> existing, Map<String, Part> desired,
                                               References refs, Map<UUID, Set<UUID>> groupsInScope) {
        List<ManualOverlap> overlaps = new ArrayList<>();
        for (CalendarPeriod period : existing) {
            if (period.getSource() != PeriodSource.MANUAL
                    || period.getSourceKey() != null && desired.containsKey(period.getSourceKey())) {
                continue;
            }
            Set<UUID> periodGroups = groupsInScope.computeIfAbsent(period.getId(),
                    id -> refs.groupsIn(period.getScope(), period.getCourses(), period.getGroupIds()));
            for (Part part : desired.values()) {
                Item item = part.item();
                boolean overlap = !period.getStartsOn().isAfter(part.endsOn())
                        && !period.getEndsOn().isBefore(part.startsOn());
                if (overlap && period.getEffect() == item.kind().getEffect()
                        && refs.groupsIn(item.scope(), item.courses(), item.groupIds()).stream()
                        .anyMatch(periodGroups::contains)) {
                    overlaps.add(new ManualOverlap(period.getId(), period.getKind().getCode(), period.getTitle(),
                            period.getStartsOn(), period.getEndsOn(), refs.codesOf(period.getGroupIds()),
                            item.sourceKey()));
                    break;
                }
            }
        }
        return overlaps;
    }

    private PeriodRow write(Planned row, List<PeriodSnapshot> snapshots, References refs, boolean dryRun) {
        CalendarPeriod stored = row.stored();
        return switch (row.action()) {
            case CREATE -> {
                Part part = row.part();
                CalendarPeriod period = new CalendarPeriod(part.semester().getId(), part.item().kind(),
                        part.item().title(), part.startsOn(), part.endsOn());
                copy(part, period);
                period.markImported(part.storedKey());
                periods.save(period);
                snapshots.add(PeriodSnapshot.of(period));
                // у пробному прогоні рядка не буде: його id нічого не означає
                yield row(row, dryRun ? null : period.getId());
            }
            case UPDATE -> {
                snapshots.add(PeriodSnapshot.of(stored));
                Part part = row.part();
                stored.setKind(part.item().kind());
                stored.setTitle(part.item().title());
                stored.setPeriod(part.startsOn(), part.endsOn());
                copy(part, stored);
                snapshots.add(PeriodSnapshot.of(stored));
                yield row(row, stored.getId());
            }
            case DELETE -> {
                snapshots.add(PeriodSnapshot.of(stored));
                PeriodRow deleted = new PeriodRow(stored.getSourceKey(), stored.getSourceKey(), ImportAction.DELETE,
                        stored.getId(), stored.getSemesterId(), stored.getKind().getCode(), stored.getStartsOn(),
                        stored.getEndsOn(), refs.codesOf(stored.getGroupIds()));
                periods.delete(stored);
                yield deleted;
            }
            case UNCHANGED, SKIP_MANUAL -> row(row, stored.getId());
        };
    }

    private static PeriodRow row(Planned row, UUID periodId) {
        Part part = row.part();
        Item item = part.item();
        return new PeriodRow(item.sourceKey(), part.storedKey(), row.action(), periodId, part.semester().getId(),
                item.kind().getCode(), part.startsOn(), part.endsOn(), item.groupCodes());
    }

    private static void copy(Part part, CalendarPeriod period) {
        Item item = part.item();
        period.setStudyMode(item.studyMode());
        period.setOrderNote(item.orderNote());
        switch (item.scope()) {
            case ALL -> period.applyToAll();
            case COURSES -> period.applyToCourses(item.courses());
            case GROUPS -> period.applyToGroups(item.groupIds());
        }
        if (item.kind().getEffect() != PeriodEffect.SUSPEND) {
            period.setSupervisors(List.of());
        }
        period.setRoomId(item.roomId());
    }

    /** Upsert of theory weeks by (semester of the year with that number, group); MANUAL rows stay. */
    private GroupSemesterCounts writeGroupWeeks(List<GroupWeeks> weeks, List<Semester> yearSemesters,
                                                String academicYear) {
        Map<Integer, Semester> byNumber = yearSemesters.stream()
                .collect(Collectors.toMap(Semester::getNumber, semester -> semester, (a, b) -> a));
        Map<String, GroupSemesterService.Row> stored = groupSemesters
                .rows(yearSemesters.stream().map(Semester::getId).toList()).stream()
                .collect(Collectors.toMap(row -> row.semesterId() + "|" + row.groupId(), row -> row));
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        List<SkippedGroupSemester> skipped = new ArrayList<>();
        List<GroupSemesterService.Row> upserts = new ArrayList<>();
        for (GroupWeeks row : weeks) {
            Semester semester = byNumber.get(row.semesterNumber());
            if (semester == null) {
                skipped.add(new SkippedGroupSemester(row.group().code(), row.semesterNumber(),
                        NO_SEMESTER.formatted(row.semesterNumber() == 1 ? "I" : "II", academicYear)));
                continue;
            }
            GroupSemesterService.Row current = stored.get(semester.getId() + "|" + row.group().id());
            if (current == null) {
                created++;
            } else if ("MANUAL".equals(current.source()) || current.sameNumbers(row.theoryWeeks(), row.exams())) {
                unchanged++;
                continue;
            } else {
                updated++;
            }
            upserts.add(new GroupSemesterService.Row(semester.getId(), row.group().id(), row.theoryWeeks(),
                    row.exams(), "IMPORT", null));
        }
        groupSemesters.upsertImported(upserts);
        return new GroupSemesterCounts(created, updated, unchanged, skipped);
    }

    private static int count(List<Planned> planned, ImportAction action) {
        return (int) planned.stream().filter(row -> row.action() == action).count();
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    /** Kinds by code or alias, groups by canonical code, rooms by name: each read once per import. */
    private static final class References {

        private final List<PeriodKind> kinds;
        private final List<GroupInfo> groups;
        private final Map<String, GroupInfo> groupsByCode = new HashMap<>();
        private final Map<UUID, GroupInfo> groupsById = new HashMap<>();
        private final Map<String, Optional<RoomInfo>> rooms = new HashMap<>();

        References(List<PeriodKind> kinds, List<GroupInfo> groups) {
            this.kinds = kinds;
            this.groups = groups;
            groups.forEach(group -> {
                groupsByCode.put(group.code(), group);
                groupsById.put(group.id(), group);
            });
        }

        Optional<PeriodKind> kind(String code) {
            return kinds.stream().filter(kind -> kind.answersTo(code)).findFirst();
        }

        Optional<GroupInfo> group(String code) {
            return GroupCodes.canonical(code).map(groupsByCode::get);
        }

        Optional<RoomInfo> room(String name, CatalogQueries catalog) {
            return rooms.computeIfAbsent(name.strip(), catalog::roomByName);
        }

        /** Groups a scope reaches (ALL: every group, COURSES: the groups of the courses). */
        Set<UUID> groupsIn(PeriodScope scope, java.util.Collection<Integer> courses, java.util.Collection<UUID> ids) {
            return switch (scope) {
                case ALL -> groupsById.keySet();
                case COURSES -> groups.stream().filter(group -> courses.contains(group.course()))
                        .map(GroupInfo::id).collect(Collectors.toSet());
                case GROUPS -> Set.copyOf(ids);
            };
        }

        List<String> codesOf(java.util.Collection<UUID> ids) {
            return ids.stream().map(groupsById::get).filter(Objects::nonNull).map(GroupInfo::code)
                    .sorted(ua.vtc.rozklad.core.catalog.UkrainianOrder.NATURAL).toList();
        }
    }

    /**
     * A resolved item.
     *
     * @param groupIds   groups of a GROUPS item in the given order (codes canonical in {@code groupCodes})
     */
    private record Item(String sourceKey, PeriodKind kind, String title, LocalDate startsOn, LocalDate endsOn,
                        PeriodScope scope, TreeSet<Integer> courses, List<UUID> groupIds, List<String> groupCodes,
                        UUID roomId, StudyMode studyMode, String orderNote) {
    }

    /** The part of an item inside one semester, stored under {@code storedKey}. */
    private record Part(Item item, Semester semester, String storedKey, LocalDate startsOn, LocalDate endsOn) {
    }

    private record Planned(Part part, CalendarPeriod stored, ImportAction action) {
    }

    private record GroupWeeks(GroupInfo group, int semesterNumber, Integer theoryWeeks, Integer exams) {
    }
}
