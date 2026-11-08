package ua.vtc.rozklad.core.substitution.internal.options;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.core.accounting.AccountingQueries;
import ua.vtc.rozklad.core.accounting.DisciplineProgress;
import ua.vtc.rozklad.core.calendar.GroupScope;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.calendar.SemesterRepository;
import ua.vtc.rozklad.core.calendar.ShiftWindows;
import ua.vtc.rozklad.core.calendar.StudyCalendar;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherDiscipline;
import ua.vtc.rozklad.core.catalog.TeacherDisciplineRepository;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.load.LoadItem;
import ua.vtc.rozklad.core.load.LoadItemRepository;
import ua.vtc.rozklad.core.load.LoadItemTeacher;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.substitution.Absence;
import ua.vtc.rozklad.core.substitution.AbsenceRepository;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.ChangeRepository;
import ua.vtc.rozklad.core.substitution.PreferenceKind;
import ua.vtc.rozklad.core.substitution.TeacherPreference;
import ua.vtc.rozklad.core.substitution.TeacherPreferenceRepository;
import ua.vtc.rozklad.core.substitution.internal.absence.LessonDecisions;
import ua.vtc.rozklad.core.substitution.internal.effect.PlannedLesson;
import ua.vtc.rozklad.core.substitution.internal.support.CatalogLookup;
import ua.vtc.rozklad.core.substitution.internal.support.SubstitutionCalendar;
import ua.vtc.rozklad.core.substitution.internal.view.LessonViews;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.LessonRepository;
import ua.vtc.rozklad.core.timetable.LessonStatus;
import ua.vtc.rozklad.core.timetable.LessonView;

/**
 * Reads everything the ranking engine needs about a lesson (a few batch queries), runs
 * {@link OptionRanker} and maps the result to {@link OptionDto}.
 */
@Service
@Transactional(readOnly = true)
public class LessonOptions {

    static final int MAKE_UP_DAYS = 14;
    static final int MAKE_UP_LIMIT = 3;
    /** A weekday without a shift-window row is not restricted. */
    private static final ShiftWindows.PairRange UNRESTRICTED = new ShiftWindows.PairRange(1, 8);
    private static final Set<ChangeStatus> ASSIGNING = EnumSet.of(ChangeStatus.PENDING_CONFIRMATION,
            ChangeStatus.CONFIRMED);
    private static final Set<ChangeStatus> SUBSTITUTION_LOAD = EnumSet.of(ChangeStatus.PENDING_CONFIRMATION,
            ChangeStatus.CONFIRMED, ChangeStatus.APPLIED);
    private static final Set<ChangeKind> TAKES_OVER = EnumSet.of(ChangeKind.REPLACE_TEACHER,
            ChangeKind.REPLACE_DISCIPLINE, ChangeKind.DISCIPLINE_FINISHED);

    private final LessonRepository lessons;
    private final SemesterRepository semesters;
    private final TeacherRepository teachers;
    private final TeacherDisciplineRepository competences;
    private final LoadItemRepository loadItems;
    private final AbsenceRepository absences;
    private final ChangeRepository changes;
    private final TeacherPreferenceRepository preferences;
    private final AccountingQueries accounting;
    private final LessonDecisions decisions;
    private final CatalogLookup catalog;
    private final SubstitutionCalendar calendar;
    private final LessonViews views;

    public LessonOptions(LessonRepository lessons, SemesterRepository semesters, TeacherRepository teachers,
                         TeacherDisciplineRepository competences, LoadItemRepository loadItems,
                         AbsenceRepository absences, ChangeRepository changes,
                         TeacherPreferenceRepository preferences, AccountingQueries accounting,
                         LessonDecisions decisions, CatalogLookup catalog, SubstitutionCalendar calendar,
                         LessonViews views) {
        this.lessons = lessons;
        this.semesters = semesters;
        this.teachers = teachers;
        this.competences = competences;
        this.loadItems = loadItems;
        this.absences = absences;
        this.changes = changes;
        this.preferences = preferences;
        this.accounting = accounting;
        this.decisions = decisions;
        this.catalog = catalog;
        this.calendar = calendar;
        this.views = views;
    }

    public OptionDto.LessonOptions of(UUID lessonId) {
        Lesson lesson = lessons.findById(lessonId).orElseThrow(() -> NotFoundException.of("Заняття", lessonId));
        if (!lesson.isScheduled()) {
            throw new BusinessRuleException("LESSON_NOT_SCHEDULED", "Заняття скасоване, варіанти заміни не потрібні.");
        }
        Semester semester = semesters.findById(lesson.getSemesterId())
                .orElseThrow(() -> NotFoundException.of("Семестр", lesson.getSemesterId()));
        StudentGroup group = catalog.group(lesson.getGroupId());
        Room room = lesson.getRoomId() == null ? null : catalog.room(lesson.getRoomId());
        Teacher absent = lesson.getTeacherId() == null ? null : catalog.teacher(lesson.getTeacherId());

        List<Lesson> day = lessons.findAllByDateAndStatus(lesson.getDate(), LessonStatus.SCHEDULED);
        List<Change> assigning = changes.findAllByDateAndStatusIn(lesson.getDate(), ASSIGNING).stream()
                .filter(change -> change.getNewTeacherId() != null)
                .toList();
        RankingInput input = new RankingInput(
                new RankingInput.Target(lesson.getId(), lesson.getDate(), lesson.getPairNumber(),
                        lesson.getSubgroupNumber(), lesson.getDisciplineId(), lesson.getTeacherId(),
                        absent == null ? null : absent.getCycleCommissionId(), room == null ? null : room.getType()),
                teachers.findAllByActiveTrueOrderByShortName().stream()
                        .map(teacher -> new RankingInput.Candidate(teacher.getId(), teacher.getShortName(),
                                teacher.getCycleCommissionId()))
                        .toList(),
                competentTeachers(semester, lesson.getDisciplineId()),
                teacherPairs(day, assigning),
                busyTeachers(day, assigning, lesson.getPairNumber()),
                absentTeachers(lesson.getDate(), lesson.getPairNumber()),
                slotPreferences(semester, lesson),
                substitutionsThisMonth(lesson.getDate()),
                groupDay(day, lesson),
                groupDisciplines(semester, lesson),
                absent == null ? List.of() : makeUpSlots(lesson, semester, group));

        List<RankedOption> ranked = OptionRanker.rank(input);
        return new OptionDto.LessonOptions(views.of(lesson), toDtos(ranked, room));
    }

    private Set<UUID> competentTeachers(Semester semester, UUID disciplineId) {
        Set<UUID> competent = competences.findAllByDisciplineId(disciplineId).stream()
                .map(TeacherDiscipline::getTeacherId)
                .collect(Collectors.toCollection(HashSet::new));
        loadItems.findAllBySemesterId(semester.getId()).stream()
                .filter(item -> item.getDisciplineId().equals(disciplineId))
                .flatMap(item -> item.getTeachers().stream())
                .map(LoadItemTeacher::getTeacherId)
                .forEach(competent::add);
        return competent;
    }

    private static Map<UUID, List<Integer>> teacherPairs(List<Lesson> day, List<Change> assigning) {
        Map<UUID, Set<Integer>> pairs = new HashMap<>();
        day.stream().filter(lesson -> lesson.getTeacherId() != null)
                .forEach(lesson -> pairs.computeIfAbsent(lesson.getTeacherId(), id -> new TreeSet<>())
                        .add(lesson.getPairNumber()));
        assigning.forEach(change -> IntStream.rangeClosed(change.getPairFrom(), change.getPairTo())
                .forEach(pair -> pairs.computeIfAbsent(change.getNewTeacherId(), id -> new TreeSet<>()).add(pair)));
        return pairs.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    private static Set<UUID> busyTeachers(List<Lesson> day, List<Change> assigning, int pair) {
        Set<UUID> busy = new HashSet<>();
        day.stream().filter(lesson -> lesson.getPairNumber() == pair && lesson.getTeacherId() != null)
                .forEach(lesson -> busy.add(lesson.getTeacherId()));
        assigning.stream().filter(change -> change.coversPair(pair))
                .forEach(change -> busy.add(change.getNewTeacherId()));
        return busy;
    }

    private Set<UUID> absentTeachers(LocalDate date, int pair) {
        return absences.findActiveOverlapping(date, date, null).stream()
                .filter(absence -> absence.covers(date, pair))
                .map(Absence::getTeacherId)
                .collect(Collectors.toSet());
    }

    private Map<UUID, PreferenceKind> slotPreferences(Semester semester, Lesson lesson) {
        return preferences.findAllBySemesterIdAndWeekdayAndPairNumber(semester.getId(),
                        lesson.getDate().getDayOfWeek().getValue(), lesson.getPairNumber()).stream()
                .collect(Collectors.toMap(TeacherPreference::getTeacherId, TeacherPreference::getKind,
                        (first, second) -> first == PreferenceKind.UNAVAILABLE ? first : second));
    }

    private Map<UUID, Integer> substitutionsThisMonth(LocalDate date) {
        YearMonth month = YearMonth.from(date);
        return changes.findAllByDateBetweenAndStatusIn(month.atDay(1), month.atEndOfMonth(), SUBSTITUTION_LOAD)
                .stream()
                .filter(change -> TAKES_OVER.contains(change.getKind()))
                .filter(change -> change.getNewTeacherId() != null && change.getOrigTeacherId() != null
                        && !change.getNewTeacherId().equals(change.getOrigTeacherId()))
                .collect(Collectors.toMap(Change::getNewTeacherId, change -> 1, Integer::sum));
    }

    private List<RankingInput.GroupLesson> groupDay(List<Lesson> day, Lesson target) {
        List<Lesson> others = day.stream()
                .filter(lesson -> lesson.getGroupId().equals(target.getGroupId()) && !lesson.getId().equals(target.getId()))
                .toList();
        Map<UUID, Change> decided = decisions.of(others);
        Map<UUID, Discipline> disciplines = catalog.disciplines(others.stream().map(Lesson::getDisciplineId).toList());
        Map<UUID, Teacher> teachersById = catalog.teachers(others.stream().map(Lesson::getTeacherId).toList());
        return others.stream().map(lesson -> new RankingInput.GroupLesson(lesson.getId(), lesson.getPairNumber(),
                        lesson.getSubgroupNumber(), lesson.getDisciplineId(),
                        disciplines.get(lesson.getDisciplineId()).getShortName(), lesson.getTeacherId(),
                        lesson.getTeacherId() == null ? null : teachersById.get(lesson.getTeacherId()).getShortName(),
                        !LessonDecisions.needsDecision(decided.get(lesson.getId()))))
                .toList();
    }

    /** Other disciplines of the group taught to the same cohort: whole group ↔ unsplit items, subgroup k ↔ its teacher. */
    private List<RankingInput.GroupDiscipline> groupDisciplines(Semester semester, Lesson lesson) {
        List<LoadItem> items = loadItems.findAllBySemesterIdAndGroupId(semester.getId(), lesson.getGroupId());
        Map<UUID, DisciplineProgress> progress = accounting.progressOfGroup(semester.getId(), lesson.getGroupId());
        int cohort = lesson.getSubgroupNumber();
        List<LoadItem> suitable = items.stream()
                .filter(item -> cohort == 0 ? !item.isSplitSubgroups() : item.isSplitSubgroups())
                .filter(item -> item.teacherOf(cohort).isPresent())
                .toList();
        Map<UUID, Discipline> disciplines = catalog.disciplines(suitable.stream().map(LoadItem::getDisciplineId).toList());
        Map<UUID, Teacher> teachersById = catalog.teachers(suitable.stream()
                .map(item -> item.teacherOf(cohort).orElseThrow()).toList());
        List<RankingInput.GroupDiscipline> result = new ArrayList<>();
        for (LoadItem item : suitable) {
            UUID teacherId = item.teacherOf(cohort).orElseThrow();
            DisciplineProgress state = progress.get(item.getDisciplineId());
            result.add(new RankingInput.GroupDiscipline(item.getDisciplineId(),
                    disciplines.get(item.getDisciplineId()).getShortName(), teacherId,
                    teachersById.get(teacherId).getShortName(), state == null ? 0 : state.hoursBehind(),
                    state != null && state.isFinished(), item.getRequiredRoomType()));
        }
        return result;
    }

    private List<RankingInput.MakeUpSlot> makeUpSlots(Lesson lesson, Semester semester, StudentGroup group) {
        LocalDate from = lesson.getDate().plusDays(1);
        LocalDate to = lesson.getDate().plusDays(MAKE_UP_DAYS);
        if (to.isAfter(semester.getEndsOn())) {
            to = semester.getEndsOn();
        }
        if (to.isBefore(from)) {
            return List.of();
        }
        UUID teacherId = lesson.getTeacherId();
        int cohort = lesson.getSubgroupNumber();
        List<Lesson> groupLessons = lessons.findAllByGroupIdAndDateBetweenOrderByDateAscPairNumberAsc(
                        group.getId(), from, to).stream()
                .filter(other -> other.isScheduled() && PlannedLesson.cohortsOverlap(other.getSubgroupNumber(), cohort))
                .toList();
        List<Lesson> teacherLessons = lessons.findAllByTeacherIdAndDateBetweenOrderByDateAscPairNumberAsc(
                teacherId, from, to).stream().filter(Lesson::isScheduled).toList();
        List<Change> open = changes.findAllByDateBetweenAndStatusIn(from, to, ASSIGNING);
        List<Absence> teacherAbsences = absences.findActiveOverlapping(from, to, teacherId);
        StudyCalendar days = calendar.range(from, to);
        GroupScope scope = SubstitutionCalendar.scope(group);

        List<MakeUpSlotFinder.Day> slots = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            LocalDate current = date;
            if (!days.day(current, scope).studyDay()) {
                continue;
            }
            Set<Integer> groupPairs = new HashSet<>();
            groupLessons.stream().filter(other -> other.getDate().equals(current))
                    .forEach(other -> groupPairs.add(other.getPairNumber()));
            open.stream()
                    .filter(change -> change.getDate().equals(current) && change.getGroupId().equals(group.getId())
                            && (change.getKind() == ChangeKind.ADDITIONAL || change.getKind() == ChangeKind.BLOCK)
                            && PlannedLesson.cohortsOverlap(change.getSubgroupNumber(), cohort))
                    .forEach(change -> IntStream.rangeClosed(change.getPairFrom(), change.getPairTo())
                            .forEach(groupPairs::add));
            Set<Integer> teacherBusy = new HashSet<>();
            teacherLessons.stream().filter(other -> other.getDate().equals(current))
                    .forEach(other -> teacherBusy.add(other.getPairNumber()));
            open.stream().filter(change -> change.getDate().equals(current) && teacherId.equals(change.getNewTeacherId()))
                    .forEach(change -> IntStream.rangeClosed(change.getPairFrom(), change.getPairTo())
                            .forEach(teacherBusy::add));
            IntStream.rangeClosed(1, 8)
                    .filter(pair -> teacherAbsences.stream().anyMatch(absence -> absence.covers(current, pair)))
                    .forEach(teacherBusy::add);
            ShiftWindows.PairRange window = calendar.shiftWindow(group.getShift(), current.getDayOfWeek().getValue())
                    .orElse(UNRESTRICTED);
            slots.add(new MakeUpSlotFinder.Day(current, window.first(), window.last(), groupPairs, teacherBusy));
        }
        return MakeUpSlotFinder.find(slots, MAKE_UP_LIMIT);
    }

    private List<OptionDto> toDtos(List<RankedOption> ranked, Room lessonRoom) {
        Map<UUID, Teacher> teachersById = catalog.teachers(ranked.stream().map(RankedOption::teacherId).toList());
        Map<UUID, Discipline> disciplines = catalog.disciplines(ranked.stream().map(RankedOption::disciplineId).toList());
        List<Lesson> swapped = lessons.findAllById(ranked.stream().map(RankedOption::swapLessonId)
                .filter(Objects::nonNull).toList());
        Map<UUID, LessonView> swapViews = views.of(swapped).stream()
                .collect(Collectors.toMap(LessonView::id, Function.identity()));
        return ranked.stream().map(option -> new OptionDto(
                option.kind(),
                option.score(),
                option.title(),
                option.reasons(),
                CatalogLookup.ref(teachersById.get(option.teacherId())),
                CatalogLookup.ref(disciplines.get(option.disciplineId())),
                option.kind() == ChangeKind.CANCEL ? null : CatalogLookup.ref(lessonRoom),
                option.swapLessonId() == null ? null : swapViews.get(option.swapLessonId()),
                option.warnings(),
                option.date(),
                option.pair())).toList();
    }
}
