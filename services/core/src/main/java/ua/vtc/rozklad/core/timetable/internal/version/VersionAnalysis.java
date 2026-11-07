package ua.vtc.rozklad.core.timetable.internal.version;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.timetable.TemplateLesson;
import ua.vtc.rozklad.core.timetable.internal.conflict.ConflictEngine;
import ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFacts;
import ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFactsLoader;
import ua.vtc.rozklad.core.timetable.internal.conflict.ConflictReport;
import ua.vtc.rozklad.core.timetable.internal.conflict.LessonDay;
import ua.vtc.rozklad.core.timetable.internal.conflict.SlotLesson;
import ua.vtc.rozklad.core.timetable.internal.metrics.MetricsDto;
import ua.vtc.rozklad.core.timetable.internal.metrics.TimetableMetricsCalculator;
import ua.vtc.rozklad.core.timetable.internal.support.LoadReadRepository;

/** Conflicts and metrics of weekly templates (one facts load for any number of versions). */
@Component
public class VersionAnalysis {

    private final ConflictEngine engine;
    private final ConflictFactsLoader factsLoader;
    private final LoadReadRepository loads;
    private final TimetableMetricsCalculator metricsCalculator;

    public VersionAnalysis(ConflictEngine engine, ConflictFactsLoader factsLoader, LoadReadRepository loads,
                           TimetableMetricsCalculator metricsCalculator) {
        this.engine = engine;
        this.factsLoader = factsLoader;
        this.loads = loads;
        this.metricsCalculator = metricsCalculator;
    }

    /** Engine view of template lessons, with the room type their load item requires. */
    public List<SlotLesson> slotLessons(Collection<TemplateLesson> lessons) {
        Map<UUID, RoomType> requiredTypes = loads.requiredRoomTypes(lessons.stream()
                .map(TemplateLesson::getLoadItemId).filter(Objects::nonNull).collect(Collectors.toSet()));
        return lessons.stream()
                .map(lesson -> slotLesson(lesson, lesson.getLoadItemId() == null ? null
                        : requiredTypes.get(lesson.getLoadItemId())))
                .toList();
    }

    public static SlotLesson slotLesson(TemplateLesson lesson, RoomType requiredRoomType) {
        return new SlotLesson(lesson.getId(), LessonDay.ofWeekday(lesson.getWeekday()), lesson.getPairNumber(),
                lesson.getParity(), lesson.getGroupId(), lesson.getSubgroupNumber(), lesson.getDisciplineId(),
                lesson.getTeacherId(), lesson.getRoomId(), lesson.getStreamId(), requiredRoomType);
    }

    public ConflictReport conflicts(UUID semesterId, Collection<SlotLesson> lessons) {
        return engine.check(lessons, factsLoader.load(lessons, semesterId));
    }

    public Result analyze(UUID semesterId, Collection<TemplateLesson> lessons) {
        List<SlotLesson> slotLessons = slotLessons(lessons);
        ConflictFacts facts = factsLoader.load(slotLessons, semesterId);
        return new Result(engine.check(slotLessons, facts), metricsCalculator.calculate(slotLessons, facts), facts);
    }

    /** Analysis of several versions of one semester, keyed like the input. */
    public Map<UUID, Result> analyzeAll(UUID semesterId, Map<UUID, List<TemplateLesson>> lessonsByVersion) {
        List<TemplateLesson> all = lessonsByVersion.values().stream().flatMap(List::stream).toList();
        List<SlotLesson> slotLessons = slotLessons(all);
        ConflictFacts facts = factsLoader.load(slotLessons, semesterId);
        Map<UUID, List<SlotLesson>> byLessonVersion = new LinkedHashMap<>();
        Map<UUID, UUID> versionOfLesson = all.stream()
                .collect(Collectors.toMap(TemplateLesson::getId, TemplateLesson::getVersionId));
        lessonsByVersion.keySet().forEach(versionId -> byLessonVersion.put(versionId, new ArrayList<>()));
        slotLessons.forEach(lesson -> byLessonVersion.get(versionOfLesson.get(lesson.id())).add(lesson));
        Map<UUID, Result> results = new LinkedHashMap<>();
        byLessonVersion.forEach((versionId, lessons) -> results.put(versionId,
                new Result(engine.check(lessons, facts), metricsCalculator.calculate(lessons, facts), facts)));
        return results;
    }

    /** @param facts catalog facts the analysis used (reused by publication for names and rooms) */
    public record Result(ConflictReport conflicts, MetricsDto metrics, ConflictFacts facts) {
    }
}
