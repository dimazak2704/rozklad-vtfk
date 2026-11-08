package ua.vtc.rozklad.core.substitution.internal.options;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.RoomRef;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.FieldValidationException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.substitution.Absence;
import ua.vtc.rozklad.core.substitution.AbsenceRepository;
import ua.vtc.rozklad.core.substitution.internal.absence.LessonDecisions;
import ua.vtc.rozklad.core.substitution.internal.options.OptionsGatherer.Day;
import ua.vtc.rozklad.core.substitution.internal.options.OptionsDeclines.Decline;
import ua.vtc.rozklad.core.substitution.internal.support.CatalogLookup;
import ua.vtc.rozklad.core.substitution.internal.view.LessonViews;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.LessonRepository;
import ua.vtc.rozklad.core.timetable.LessonView;

/**
 * Ranked options of a lesson and the day plan of an absence (DOMAIN §7): {@link OptionsGatherer}
 * reads the day once, {@link OptionRanker} / {@link DayPlanner} rank, this class maps to DTOs.
 */
@Service
@Transactional(readOnly = true)
public class LessonOptions {

    private final LessonRepository lessons;
    private final AbsenceRepository absences;
    private final OptionsGatherer gatherer;
    private final LessonViews views;

    public LessonOptions(LessonRepository lessons, AbsenceRepository absences, OptionsGatherer gatherer,
                         LessonViews views) {
        this.lessons = lessons;
        this.absences = absences;
        this.gatherer = gatherer;
        this.views = views;
    }

    /** {@code GET /api/admin/lessons/{id}/options}. */
    public OptionDto.LessonOptions of(UUID lessonId) {
        Lesson lesson = scheduledLesson(lessonId);
        Day day = gatherer.forDay(lesson.getDate(), List.of(lesson));
        RankingInput input = day.inputs().get(lesson.getId());
        List<RankedOption> ranked = OptionRanker.rank(input);
        Map<UUID, LessonView> lessonViews = viewsOf(day, List.of(lesson), List.of(ranked));
        return toDto(lesson, input, ranked, day, lessonViews);
    }

    /** The ranking as the dispatcher sees it now (for the recommendation log). */
    public List<RankedOption> ranked(UUID lessonId) {
        Lesson lesson = scheduledLesson(lessonId);
        return OptionRanker.rank(gatherer.forDay(lesson.getDate(), List.of(lesson)).inputs().get(lesson.getId()));
    }

    /**
     * {@code GET /api/admin/absences/{id}/plan?date=}: a proposal for every undecided lesson of the
     * absent teacher on the date (default: the absence's first day). Nothing is created or sent.
     */
    public DayPlanDto plan(UUID absenceId, LocalDate requestedDate) {
        Absence absence = absences.findById(absenceId).orElseThrow(() -> NotFoundException.of("Відсутність", absenceId));
        LocalDate date = requestedDate != null ? requestedDate : absence.getDateFrom();
        if (date.isBefore(absence.getDateFrom()) || date.isAfter(absence.getDateTo())) {
            throw FieldValidationException.of("date", "Дата поза періодом відсутності.");
        }
        List<Lesson> covered = lessons.findAllByTeacherIdAndDateBetweenOrderByDateAscPairNumberAsc(
                        absence.getTeacherId(), date, date).stream()
                .filter(Lesson::isScheduled)
                .filter(lesson -> absence.covers(date, lesson.getPairNumber()))
                .toList();
        if (covered.isEmpty()) {
            return new DayPlanDto(date, List.of());
        }
        // рішення про заняття читає збирач разом із днем групи — окремий запит не потрібен
        Day day = gatherer.forDay(date, covered);
        List<Lesson> undecided = covered.stream()
                .filter(lesson -> LessonDecisions.needsDecision(day.decisions().get(lesson.getId())))
                .toList();
        if (undecided.isEmpty()) {
            return new DayPlanDto(date, List.of());
        }
        Map<UUID, UUID> groupOf = undecided.stream().collect(Collectors.toMap(Lesson::getId, Lesson::getGroupId));
        List<DayPlanner.Planned> planned = DayPlanner.plan(undecided.stream()
                .map(lesson -> day.inputs().get(lesson.getId())).toList(), groupOf);
        Map<UUID, LessonView> lessonViews = viewsOf(day, undecided,
                planned.stream().map(DayPlanner.Planned::options).toList());
        List<DayPlanDto.PlannedLesson> result = new ArrayList<>();
        planned.stream()
                .sorted(Comparator.comparingInt((DayPlanner.Planned item) -> item.input().target().pair())
                        .thenComparing(item -> lessonViews.get(item.input().target().lessonId()).group().code()))
                .forEach(item -> {
                    Lesson lesson = day.lessonsById().get(item.input().target().lessonId());
                    OptionDto.LessonOptions options = toDto(lesson, item.input(), item.options(), day, lessonViews);
                    result.add(new DayPlanDto.PlannedLesson(options.lesson(), options.options(), item.proposedIndex(),
                            options.declined(), options.past()));
                });
        return new DayPlanDto(date, result);
    }

    private Lesson scheduledLesson(UUID lessonId) {
        Lesson lesson = lessons.findById(lessonId).orElseThrow(() -> NotFoundException.of("Заняття", lessonId));
        if (!lesson.isScheduled()) {
            throw new BusinessRuleException("LESSON_NOT_SCHEDULED", "Заняття скасоване, варіанти заміни не потрібні.");
        }
        return lesson;
    }

    /** Views of the target lessons and of the lessons the options move or merge, in one read. */
    private Map<UUID, LessonView> viewsOf(Day day, List<Lesson> targets, List<List<RankedOption>> options) {
        List<Lesson> shown = Stream.concat(targets.stream(), options.stream().flatMap(List::stream)
                        .map(RankedOption::swapLessonId).filter(Objects::nonNull)
                        .map(id -> day.lessonsById().get(id)).filter(Objects::nonNull))
                .distinct()
                .toList();
        List<LessonView> result = views.of(shown);
        Map<UUID, LessonView> byId = new LinkedHashMap<>();
        for (int index = 0; index < shown.size(); index++) {
            byId.put(shown.get(index).getId(), result.get(index));
        }
        return byId;
    }

    private static OptionDto.LessonOptions toDto(Lesson lesson, RankingInput input, List<RankedOption> ranked, Day day,
                                                 Map<UUID, LessonView> lessonViews) {
        Room lessonRoom = lesson.getRoomId() == null ? null : day.rooms().get(lesson.getRoomId());
        List<OptionDto> options = ranked.stream().map(option -> new OptionDto(
                option.kind(),
                option.score(),
                option.title(),
                option.reasons(),
                CatalogLookup.ref(option.teacherId() == null ? null : day.teachers().get(option.teacherId())),
                CatalogLookup.ref(option.disciplineId() == null ? null : day.disciplines().get(option.disciplineId())),
                option.kind() == ChangeKind.CANCEL ? null : CatalogLookup.ref(lessonRoom),
                option.swapLessonId() == null ? null : lessonViews.get(option.swapLessonId()),
                option.warnings(),
                null,
                null,
                option.band(),
                option.recommended(),
                suggestedRoom(option, lessonRoom, day))).toList();
        Map<UUID, Decline> latest = new LinkedHashMap<>();
        day.declines().getOrDefault(lesson.getId(), List.of())
                .forEach(decline -> latest.put(decline.teacherId(), decline));
        List<OptionDto.Declined> declined = latest.values().stream()
                .sorted(Comparator.comparing(Decline::at))
                .map(decline -> new OptionDto.Declined(CatalogLookup.ref(day.teachers().get(decline.teacherId())),
                        decline.at()))
                .toList();
        return new OptionDto.LessonOptions(lessonViews.get(lesson.getId()), options, declined, input.target().past());
    }

    /** RD: the substitute's own free room, else the lesson's; MERGE: the merged class's room. */
    private static RoomRef suggestedRoom(RankedOption option, Room lessonRoom, Day day) {
        return switch (option.kind()) {
            case REPLACE_DISCIPLINE -> CatalogLookup.ref(option.roomId() != null ? day.rooms().get(option.roomId())
                    : lessonRoom);
            case MERGE_SUBGROUPS -> CatalogLookup.ref(option.roomId() == null ? null : day.rooms().get(option.roomId()));
            default -> null;
        };
    }
}
