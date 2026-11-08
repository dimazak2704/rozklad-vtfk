package ua.vtc.rozklad.core.substitution.internal.options;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.core.accounting.AccountingQueries;
import ua.vtc.rozklad.core.accounting.DisciplineProgress;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.load.LoadItem;
import ua.vtc.rozklad.core.load.LoadItemRepository;
import ua.vtc.rozklad.core.substitution.Absence;
import ua.vtc.rozklad.core.substitution.AbsenceRepository;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.ChangeRepository;
import ua.vtc.rozklad.core.substitution.PreferenceKind;
import ua.vtc.rozklad.core.substitution.TeacherPreference;
import ua.vtc.rozklad.core.substitution.TeacherPreferenceRepository;
import ua.vtc.rozklad.core.substitution.internal.absence.LessonDecisions;
import ua.vtc.rozklad.core.substitution.internal.effect.PlannedLesson;
import ua.vtc.rozklad.core.substitution.internal.options.OptionsDeclines.Decline;
import ua.vtc.rozklad.core.substitution.internal.options.RankingInput.GroupDiscipline;
import ua.vtc.rozklad.core.substitution.internal.options.RankingInput.GroupLesson;
import ua.vtc.rozklad.core.substitution.internal.options.RankingInput.Progress;
import ua.vtc.rozklad.core.substitution.internal.options.RankingInput.Target;
import ua.vtc.rozklad.core.substitution.internal.support.CatalogLookup;
import ua.vtc.rozklad.core.substitution.internal.support.ChangeLineage;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.LessonOrigin;
import ua.vtc.rozklad.core.timetable.LessonRepository;
import ua.vtc.rozklad.core.timetable.LessonStatus;

/**
 * Reads everything the ranking needs for the lessons of one date with a fixed number of queries
 * (DOMAIN §7): the day's lessons, open changes, absences, preferences, declines, the cover ledger of
 * six weeks and the teaching load once for the day; load items, accounting and usual rooms once per
 * group. The per-lesson endpoint is {@code forDay} with one lesson. Callers run it in a read-only
 * transaction.
 *
 * <p>A teacher is busy at a pair with a SCHEDULED lesson or an open assignment there, except a
 * lesson an open change takes away (its original or swapped lesson, other than a room change) and a
 * lesson or assignment of the target's own stream.
 */
@Component
class OptionsGatherer {

    /** Window of the cover burden (DOMAIN §7). */
    static final int COVER_DAYS = 42;
    static final int RECENT_COVER_DAYS = 14;
    private static final Set<ChangeStatus> OPEN = EnumSet.of(ChangeStatus.DRAFT, ChangeStatus.PENDING_CONFIRMATION,
            ChangeStatus.CONFIRMED);
    private static final Set<ChangeStatus> DONE = EnumSet.of(ChangeStatus.CONFIRMED, ChangeStatus.APPLIED);

    private final LessonRepository lessons;
    private final LoadItemRepository loadItems;
    private final AbsenceRepository absences;
    private final ChangeRepository changes;
    private final TeacherPreferenceRepository preferences;
    private final AccountingQueries accounting;
    private final LessonDecisions decisions;
    private final CatalogLookup catalog;
    private final OptionsDeclines declines;
    private final EntityManager entityManager;
    private final Clock clock;

    OptionsGatherer(LessonRepository lessons, LoadItemRepository loadItems, AbsenceRepository absences,
                    ChangeRepository changes, TeacherPreferenceRepository preferences, AccountingQueries accounting,
                    LessonDecisions decisions, CatalogLookup catalog, OptionsDeclines declines,
                    EntityManager entityManager, Clock clock) {
        this.lessons = lessons;
        this.loadItems = loadItems;
        this.absences = absences;
        this.changes = changes;
        this.preferences = preferences;
        this.accounting = accounting;
        this.decisions = decisions;
        this.catalog = catalog;
        this.declines = declines;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** Usual room of a teacher for a discipline in a group (number of template lessons there). */
    record RoomUse(UUID groupId, UUID teacherId, UUID disciplineId, UUID roomId, long lessons) {
    }

    /**
     * Inputs of every target lesson (all of {@code date}, SCHEDULED, one semester), plus what the
     * DTOs need: the lessons by id (targets and their group-day), the current decision of each of
     * them, declines per lesson and the catalog entries the options mention.
     */
    record Day(LocalDate date, Map<UUID, RankingInput> inputs, Map<UUID, Lesson> lessonsById,
               Map<UUID, Change> decisions,
               Map<UUID, List<Decline>> declines, Map<UUID, Teacher> teachers, Map<UUID, Discipline> disciplines,
               Map<UUID, Room> rooms) {
    }

    Day forDay(LocalDate date, List<Lesson> targets) {
        LocalDate today = LocalDate.now(clock);
        int hoursPerPair = accounting.hoursPerPair();
        UUID semesterId = targets.getFirst().getSemesterId();
        List<Lesson> day = lessons.findAllByDateAndStatus(date, LessonStatus.SCHEDULED);
        Map<UUID, Lesson> dayById = day.stream().collect(Collectors.toMap(Lesson::getId, lesson -> lesson));
        List<Change> open = changes.findAllByDateAndStatusIn(date, OPEN);
        Set<UUID> freed = freedLessons(open, day);
        List<Absence> absent = absences.findActiveOverlapping(date, date, null);
        Map<UUID, Map<Integer, PreferenceKind>> prefs = preferencesOf(semesterId, date);
        List<Decline> declineRows = declines.onDate(date);

        Set<UUID> groupIds = targets.stream().map(Lesson::getGroupId).collect(Collectors.toCollection(TreeSet::new));
        Map<UUID, List<Lesson>> dayByGroup = day.stream().filter(lesson -> groupIds.contains(lesson.getGroupId()))
                .collect(Collectors.groupingBy(Lesson::getGroupId));
        Map<UUID, Change> decided = decisions.of(dayByGroup.values().stream().flatMap(List::stream).toList());
        Map<UUID, List<LoadItem>> itemsByGroup = new HashMap<>();
        Map<UUID, Map<UUID, DisciplineProgress>> progressByGroup = new HashMap<>();
        for (UUID groupId : groupIds) {
            itemsByGroup.put(groupId, loadItems.findAllBySemesterIdAndGroupId(semesterId, groupId));
            progressByGroup.put(groupId, accounting.progressOfGroup(semesterId, groupId));
        }
        Map<String, RoomUse> usualRooms = usualRooms(semesterId, groupIds);

        Set<UUID> pool = itemsByGroup.values().stream().flatMap(List::stream)
                .flatMap(item -> item.getTeachers().stream().map(teacher -> teacher.getTeacherId()))
                .collect(Collectors.toSet());
        Map<UUID, BigDecimal> weekly = weeklyHours(semesterId, pool);
        CoverLedger ledger = coverLedger(date, open);

        Set<UUID> roomIds = new HashSet<>();
        targets.forEach(lesson -> roomIds.add(lesson.getRoomId()));
        usualRooms.values().forEach(use -> roomIds.add(use.roomId()));
        dayByGroup.values().stream().flatMap(List::stream).forEach(lesson -> roomIds.add(lesson.getRoomId()));
        Map<UUID, Room> rooms = catalog.rooms(roomIds);
        Set<UUID> teacherIds = new HashSet<>(pool);
        dayByGroup.values().stream().flatMap(List::stream).forEach(lesson -> teacherIds.add(lesson.getTeacherId()));
        declineRows.forEach(decline -> teacherIds.add(decline.teacherId()));
        Map<UUID, Teacher> teachers = catalog.teachers(teacherIds);
        Set<UUID> disciplineIds = new HashSet<>();
        itemsByGroup.values().stream().flatMap(List::stream).forEach(item -> disciplineIds.add(item.getDisciplineId()));
        dayByGroup.values().stream().flatMap(List::stream).forEach(lesson -> disciplineIds.add(lesson.getDisciplineId()));
        Map<UUID, Discipline> disciplines = catalog.disciplines(disciplineIds);

        Map<UUID, RankingInput> inputs = new LinkedHashMap<>();
        Map<UUID, List<Decline>> declinesByLesson = declineRows.stream()
                .filter(decline -> decline.lessonId() != null)
                .collect(Collectors.groupingBy(Decline::lessonId));
        for (Lesson target : targets) {
            List<Lesson> groupDay = dayByGroup.getOrDefault(target.getGroupId(), List.of()).stream()
                    .filter(lesson -> !lesson.getId().equals(target.getId()))
                    .toList();
            Map<UUID, DisciplineProgress> progress = progressByGroup.get(target.getGroupId());
            List<LoadItem> items = itemsByGroup.get(target.getGroupId());
            Room room = target.getRoomId() == null ? null : rooms.get(target.getRoomId());
            Target described = new Target(target.getId(), date, today, target.getPairNumber(),
                    target.getSubgroupNumber(), target.getDisciplineId(), shortName(disciplines, target.getDisciplineId()),
                    target.getTeacherId(), room == null ? null : room.getType(), target.getStreamId(), target.isOnline(),
                    hoursPerPair, hasBlock(target, open, groupDay),
                    progressOf(progress.get(target.getDisciplineId()), items, target.getDisciplineId(),
                            target.getSubgroupNumber()));
            Set<UUID> declinedHere = declinesByLesson.getOrDefault(target.getId(), List.of()).stream()
                    .map(Decline::teacherId).collect(Collectors.toSet());
            Set<UUID> declinedElsewhere = declineRows.stream()
                    .filter(decline -> !target.getId().equals(decline.lessonId()))
                    .map(Decline::teacherId).collect(Collectors.toSet());
            inputs.put(target.getId(), new RankingInput(described,
                    groupLessons(groupDay, decided, freed, disciplines, teachers),
                    pool(target, items, progress, usualRooms, day, open, freed, disciplines, teachers),
                    teacherPairs(day, open, freed),
                    busyByPair(target, day, open, freed, dayById),
                    absentByPair(absent, date),
                    prefs.entrySet().stream()
                            .filter(entry -> entry.getValue().containsKey(target.getPairNumber()))
                            .collect(Collectors.toMap(Map.Entry::getKey,
                                    entry -> entry.getValue().get(target.getPairNumber()))),
                    weekly, ledger.burden(), ledger.count(), ledger.last(), declinedHere, declinedElsewhere,
                    Map.of()));
        }
        Map<UUID, Lesson> lessonsById = new HashMap<>();
        targets.forEach(lesson -> lessonsById.put(lesson.getId(), lesson));
        dayByGroup.values().stream().flatMap(List::stream).forEach(lesson -> lessonsById.put(lesson.getId(), lesson));
        return new Day(date, inputs, lessonsById, decided, declinesByLesson, teachers, disciplines, rooms);
    }

    // ---------------------------------------------------------------- one query each

    private Map<UUID, Map<Integer, PreferenceKind>> preferencesOf(UUID semesterId, LocalDate date) {
        Map<UUID, Map<Integer, PreferenceKind>> result = new HashMap<>();
        for (TeacherPreference preference : preferences.findAllBySemesterIdAndWeekday(semesterId,
                date.getDayOfWeek().getValue())) {
            result.computeIfAbsent(preference.getTeacherId(), teacher -> new HashMap<>())
                    .merge(preference.getPairNumber(), preference.getKind(),
                            (first, second) -> first == PreferenceKind.UNAVAILABLE ? first : second);
        }
        return result;
    }

    /** Σ hours per week of every load item of the semester the teachers teach (any subgroup). */
    private Map<UUID, BigDecimal> weeklyHours(UUID semesterId, Collection<UUID> teacherIds) {
        if (teacherIds.isEmpty()) {
            return Map.of();
        }
        return entityManager.createQuery("""
                        select t.teacherId, sum(i.hoursPerWeek)
                        from LoadItem i join i.teachers t
                        where i.semesterId = :semesterId and t.teacherId in :teacherIds
                        group by t.teacherId
                        """, Object[].class)
                .setParameter("semesterId", semesterId)
                .setParameter("teacherIds", teacherIds)
                .getResultStream()
                .collect(Collectors.toMap(row -> (UUID) row[0],
                        row -> row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1]));
    }

    /** The room each teacher has a discipline in most often with each group (template lessons of the semester). */
    private Map<String, RoomUse> usualRooms(UUID semesterId, Collection<UUID> groupIds) {
        List<RoomUse> uses = entityManager.createQuery("""
                        select l.groupId, l.teacherId, l.disciplineId, l.roomId, count(l)
                        from Lesson l
                        where l.semesterId = :semesterId and l.groupId in :groupIds
                          and l.origin = :origin and l.roomId is not null and l.teacherId is not null
                        group by l.groupId, l.teacherId, l.disciplineId, l.roomId
                        """, Object[].class)
                .setParameter("semesterId", semesterId)
                .setParameter("groupIds", groupIds)
                .setParameter("origin", LessonOrigin.TEMPLATE)
                .getResultStream()
                .map(row -> new RoomUse((UUID) row[0], (UUID) row[1], (UUID) row[2], (UUID) row[3],
                        ((Number) row[4]).longValue()))
                .toList();
        Map<String, RoomUse> best = new HashMap<>();
        for (RoomUse use : uses) {
            best.merge(roomKey(use.groupId(), use.teacherId(), use.disciplineId()), use,
                    (first, second) -> first.lessons() != second.lessons()
                            ? (first.lessons() > second.lessons() ? first : second)
                            : (first.roomId().compareTo(second.roomId()) <= 0 ? first : second));
        }
        return best;
    }

    private static String roomKey(UUID groupId, UUID teacherId, UUID disciplineId) {
        return groupId + "|" + teacherId + "|" + disciplineId;
    }

    /**
     * Cover pairs of the last six weeks (confirmed or applied takeovers that a later change did not
     * undo) weighted 1.0 up to 14 days old and 0.5 up to 42, plus 1.0 for each open request of the
     * date itself.
     */
    private CoverLedger coverLedger(LocalDate date, List<Change> open) {
        List<Change> done = changes.findAllByDateBetweenAndStatusIn(date.minusDays(COVER_DAYS), date, DONE);
        Set<UUID> undone = ChangeLineage.of(done, lessons).undoneSubstitutions();
        Map<UUID, Double> burden = new HashMap<>();
        Map<UUID, Integer> count = new HashMap<>();
        Map<UUID, LocalDate> last = new HashMap<>();
        Stream.concat(done.stream().filter(change -> !undone.contains(change.getId())),
                        open.stream().filter(change -> change.getStatus() == ChangeStatus.PENDING_CONFIRMATION))
                .filter(ChangeLineage::takesOver)
                .forEach(change -> {
                    long age = ChronoUnit.DAYS.between(change.getDate(), date);
                    double weight = change.getStatus() == ChangeStatus.PENDING_CONFIRMATION || age <= RECENT_COVER_DAYS
                            ? 1.0 : 0.5;
                    UUID teacher = change.getNewTeacherId();
                    burden.merge(teacher, weight * change.pairCount(), Double::sum);
                    count.merge(teacher, change.pairCount(), Integer::sum);
                    last.merge(teacher, change.getDate(), (first, second) -> first.isAfter(second) ? first : second);
                });
        return new CoverLedger(burden, count, last);
    }

    private record CoverLedger(Map<UUID, Double> burden, Map<UUID, Integer> count, Map<UUID, LocalDate> last) {
    }

    // ---------------------------------------------------------------- per lesson, in memory

    /** Lessons an open change takes away from their teacher (cancel, replace, swap, merge, block). */
    private static Set<UUID> freedLessons(List<Change> open, List<Lesson> day) {
        Set<UUID> freed = new HashSet<>();
        open.stream()
                .filter(change -> change.getKind() != ChangeKind.ROOM_CHANGE)
                .flatMap(change -> Stream.of(change.getOriginalLessonId(), change.getSwapLessonId()))
                .filter(Objects::nonNull)
                .forEach(freed::add);
        open.stream()
                .filter(change -> change.getKind() == ChangeKind.BLOCK)
                .forEach(block -> day.stream()
                        .filter(lesson -> lesson.getGroupId().equals(block.getGroupId())
                                && block.coversPair(lesson.getPairNumber())
                                && PlannedLesson.cohortsOverlap(lesson.getSubgroupNumber(), block.getSubgroupNumber()))
                        .forEach(lesson -> freed.add(lesson.getId())));
        return freed;
    }

    private static boolean hasBlock(Lesson target, List<Change> open, List<Lesson> groupDay) {
        return groupDay.stream().anyMatch(lesson -> lesson.getOrigin() == LessonOrigin.BLOCK)
                || open.stream().anyMatch(change -> change.getKind() == ChangeKind.BLOCK
                && change.getGroupId().equals(target.getGroupId()));
    }

    private static List<GroupLesson> groupLessons(List<Lesson> groupDay, Map<UUID, Change> decided, Set<UUID> freed,
                                                  Map<UUID, Discipline> disciplines, Map<UUID, Teacher> teachers) {
        return groupDay.stream()
                .sorted(Comparator.comparingInt(Lesson::getPairNumber).thenComparingInt(Lesson::getSubgroupNumber))
                .map(lesson -> new GroupLesson(lesson.getId(), lesson.getPairNumber(), lesson.getSubgroupNumber(),
                        lesson.getDisciplineId(), shortName(disciplines, lesson.getDisciplineId()),
                        lesson.getTeacherId(), teacherName(teachers, lesson.getTeacherId()), lesson.getRoomId(),
                        !LessonDecisions.needsDecision(decided.get(lesson.getId())) || freed.contains(lesson.getId())))
                .toList();
    }

    /**
     * The pool (H2): whole group ↔ unsplit items, subgroup k ↔ split items with the teacher of k;
     * one entry per discipline and teacher, with the usual room when it is free at the pair.
     */
    private static List<GroupDiscipline> pool(Lesson target, List<LoadItem> items,
                                              Map<UUID, DisciplineProgress> progress, Map<String, RoomUse> usualRooms,
                                              List<Lesson> day, List<Change> open, Set<UUID> freed,
                                              Map<UUID, Discipline> disciplines, Map<UUID, Teacher> teachers) {
        int cohort = target.getSubgroupNumber();
        Map<String, GroupDiscipline> result = new LinkedHashMap<>();
        for (LoadItem item : items) {
            if (cohort == 0 ? item.isSplitSubgroups() : !item.isSplitSubgroups()) {
                continue;
            }
            UUID teacherId = item.teacherOf(cohort).orElse(null);
            if (teacherId == null) {
                continue;
            }
            String key = item.getDisciplineId() + "|" + teacherId;
            if (result.containsKey(key)) {
                continue;
            }
            RoomUse usual = usualRooms.get(roomKey(target.getGroupId(), teacherId, item.getDisciplineId()));
            UUID ownRoom = usual != null && roomFree(usual.roomId(), target, day, open, freed) ? usual.roomId() : null;
            result.put(key, new GroupDiscipline(item.getDisciplineId(), shortName(disciplines, item.getDisciplineId()),
                    teacherId, teacherName(teachers, teacherId),
                    progressOf(progress.get(item.getDisciplineId()), items, item.getDisciplineId(), cohort),
                    item.getRequiredRoomType(), ownRoom));
        }
        return List.copyOf(result.values());
    }

    /** No exclusive lesson (other than a freed one or the target) and no open change uses the room at the pair. */
    private static boolean roomFree(UUID roomId, Lesson target, List<Lesson> day, List<Change> open, Set<UUID> freed) {
        int pair = target.getPairNumber();
        boolean taken = day.stream().anyMatch(lesson -> lesson.getPairNumber() == pair
                && roomId.equals(lesson.getRoomId()) && lesson.isRoomExclusive() && !lesson.isOnline()
                && !lesson.getId().equals(target.getId()) && !freed.contains(lesson.getId()));
        boolean promised = open.stream().anyMatch(change -> change.coversPair(pair)
                && roomId.equals(change.getNewRoomId()));
        return !taken && !promised;
    }

    private static Progress progressOf(DisciplineProgress progress, List<LoadItem> items, UUID disciplineId,
                                       int cohort) {
        if (progress == null) {
            return null;
        }
        BigDecimal hoursPerWeek = items.stream()
                .filter(item -> item.getDisciplineId().equals(disciplineId))
                .filter(item -> cohort == 0 || !item.isSplitSubgroups() || item.teacherOf(cohort).isPresent())
                .map(LoadItem::getHoursPerWeek)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Progress(progress.plan(), progress.fact(), progress.forecast(), progress.status(), hoursPerWeek);
    }

    /** Pairs of every teacher that day: kept lessons and open assignments. */
    private static Map<UUID, List<Integer>> teacherPairs(List<Lesson> day, List<Change> open, Set<UUID> freed) {
        Map<UUID, Set<Integer>> pairs = new HashMap<>();
        day.stream()
                .filter(lesson -> lesson.getTeacherId() != null && !freed.contains(lesson.getId()))
                .forEach(lesson -> pairs.computeIfAbsent(lesson.getTeacherId(), id -> new TreeSet<>())
                        .add(lesson.getPairNumber()));
        open.stream().filter(change -> change.getNewTeacherId() != null)
                .forEach(change -> IntStream.rangeClosed(change.getPairFrom(), change.getPairTo())
                        .forEach(pair -> pairs.computeIfAbsent(change.getNewTeacherId(), id -> new TreeSet<>())
                                .add(pair)));
        return pairs.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    /**
     * Busy teachers per pair: kept lessons and open assignments, except those of the target's own
     * stream (H5: one class for several groups may get one substitute).
     */
    private static Map<Integer, Set<UUID>> busyByPair(Lesson target, List<Lesson> day, List<Change> open,
                                                      Set<UUID> freed, Map<UUID, Lesson> dayById) {
        UUID stream = target.getStreamId();
        Map<Integer, Set<UUID>> busy = new HashMap<>();
        day.stream()
                .filter(lesson -> lesson.getTeacherId() != null && !freed.contains(lesson.getId()))
                .filter(lesson -> stream == null || !stream.equals(lesson.getStreamId()))
                .forEach(lesson -> busy.computeIfAbsent(lesson.getPairNumber(), pair -> new HashSet<>())
                        .add(lesson.getTeacherId()));
        open.stream().filter(change -> change.getNewTeacherId() != null)
                .filter(change -> {
                    Lesson original = change.getOriginalLessonId() == null ? null
                            : dayById.get(change.getOriginalLessonId());
                    return stream == null || original == null || !stream.equals(original.getStreamId());
                })
                .forEach(change -> IntStream.rangeClosed(change.getPairFrom(), change.getPairTo())
                        .forEach(pair -> busy.computeIfAbsent(pair, key -> new HashSet<>())
                                .add(change.getNewTeacherId())));
        return busy;
    }

    private static Map<Integer, Set<UUID>> absentByPair(List<Absence> absent, LocalDate date) {
        Map<Integer, Set<UUID>> result = new HashMap<>();
        for (int pair = 1; pair <= 8; pair++) {
            int current = pair;
            Set<UUID> teachers = absent.stream().filter(absence -> absence.covers(date, current))
                    .map(Absence::getTeacherId).collect(Collectors.toSet());
            if (!teachers.isEmpty()) {
                result.put(pair, teachers);
            }
        }
        return result;
    }

    private static String shortName(Map<UUID, Discipline> disciplines, UUID id) {
        Discipline discipline = id == null ? null : disciplines.get(id);
        return discipline == null ? "?" : discipline.getShortName();
    }

    private static String teacherName(Map<UUID, Teacher> teachers, UUID id) {
        Teacher teacher = id == null ? null : teachers.get(id);
        return teacher == null ? null : teacher.getShortName();
    }
}
