package ua.vtc.rozklad.core.timetable.internal.version;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.timetable.TemplateLesson;
import ua.vtc.rozklad.core.timetable.internal.support.CatalogRefs;

/** Template lessons → {@link TemplateLessonDto} with catalog references loaded in bulk. */
@Component
public class TemplateLessonMapper {

    private static final Comparator<TemplateLessonDto> GRID_ORDER = Comparator
            .comparingInt(TemplateLessonDto::weekday)
            .thenComparingInt(TemplateLessonDto::pair)
            .thenComparing(dto -> dto.group() == null ? "" : dto.group().code())
            .thenComparingInt(TemplateLessonDto::subgroup)
            .thenComparing(TemplateLessonDto::parity);

    private final CatalogRefs catalogRefs;

    public TemplateLessonMapper(CatalogRefs catalogRefs) {
        this.catalogRefs = catalogRefs;
    }

    /** DTOs in grid order (weekday, pair, group, subgroup, parity). */
    public List<TemplateLessonDto> toDtos(Collection<TemplateLesson> lessons) {
        CatalogRefs.Refs refs = refsOf(lessons);
        return lessons.stream()
                .map(lesson -> map(lesson, refs, lesson.getWeekday(), lesson.getPairNumber(), lesson.getParity(),
                        lesson.getRoomId()))
                .sorted(GRID_ORDER)
                .toList();
    }

    public TemplateLessonDto toDto(TemplateLesson lesson) {
        return toDtos(List.of(lesson)).getFirst();
    }

    /** The lesson as it would look at another slot (drag & drop preview); the entity is not touched. */
    public TemplateLessonDto preview(TemplateLesson lesson, int weekday, int pair, Parity parity, UUID roomId) {
        CatalogRefs.Refs refs = catalogRefs.load(List.of(lesson.getGroupId()), List.of(lesson.getDisciplineId()),
                nullable(lesson.getTeacherId()), nullable(roomId));
        return map(lesson, refs, weekday, pair, parity, roomId);
    }

    /** Converter that shares one bulk load of references between several lesson lists (diff). */
    public Function<TemplateLesson, TemplateLessonDto> converter(Collection<TemplateLesson> lessons) {
        CatalogRefs.Refs refs = refsOf(lessons);
        return lesson -> map(lesson, refs, lesson.getWeekday(), lesson.getPairNumber(), lesson.getParity(),
                lesson.getRoomId());
    }

    private CatalogRefs.Refs refsOf(Collection<TemplateLesson> lessons) {
        return catalogRefs.load(
                ids(lessons, TemplateLesson::getGroupId),
                ids(lessons, TemplateLesson::getDisciplineId),
                ids(lessons, TemplateLesson::getTeacherId),
                ids(lessons, TemplateLesson::getRoomId));
    }

    private static TemplateLessonDto map(TemplateLesson lesson, CatalogRefs.Refs refs, int weekday, int pair,
                                         Parity parity, UUID roomId) {
        return new TemplateLessonDto(lesson.getId(), weekday, pair, parity, refs.group(lesson.getGroupId()),
                lesson.getSubgroupNumber(), refs.discipline(lesson.getDisciplineId()),
                refs.teacher(lesson.getTeacherId()), refs.room(roomId), lesson.getLessonKind(),
                lesson.getLoadItemId(), lesson.isPinned(), lesson.isOnline(), lesson.getStreamId(),
                lesson.getVersion());
    }

    private static List<UUID> ids(Collection<TemplateLesson> lessons, Function<TemplateLesson, UUID> id) {
        return lessons.stream().map(id).filter(Objects::nonNull).distinct().collect(Collectors.toList());
    }

    private static List<UUID> nullable(UUID id) {
        return id == null ? List.of() : List.of(id);
    }
}
