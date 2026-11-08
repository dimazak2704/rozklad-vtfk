package ua.vtc.rozklad.core.load.internal;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.catalog.CatalogQueries;
import ua.vtc.rozklad.core.catalog.DisciplineRef;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.catalog.RoomRef;
import ua.vtc.rozklad.core.catalog.TeacherRef;
import ua.vtc.rozklad.core.catalog.UkrainianOrder;
import ua.vtc.rozklad.core.load.LoadItem;
import ua.vtc.rozklad.core.load.LoadItemTeacher;
import ua.vtc.rozklad.core.load.LoadPattern;
import ua.vtc.rozklad.core.load.internal.LoadDtos.LoadItemDto;
import ua.vtc.rozklad.core.load.internal.LoadDtos.LoadTeacherDto;
import ua.vtc.rozklad.core.settings.SettingsService;

/** Builds {@link LoadItemDto}s with catalog references loaded in one batch per type. */
@Component
class LoadItemMapper {

    private final CatalogQueries catalog;
    private final SettingsService settings;

    LoadItemMapper(CatalogQueries catalog, SettingsService settings) {
        this.catalog = catalog;
        this.settings = settings;
    }

    /** DTOs ordered by group code, then discipline short name. */
    List<LoadItemDto> toDtos(Collection<LoadItem> items) {
        Map<UUID, GroupRef> groups = catalog.groupRefs(ids(items, LoadItem::getGroupId));
        Map<UUID, DisciplineRef> disciplines = catalog.disciplineRefs(ids(items, LoadItem::getDisciplineId));
        Map<UUID, RoomRef> rooms = catalog.roomRefs(ids(items, LoadItem::getPreferredRoomId));
        Map<UUID, TeacherRef> teachers = catalog.teacherRefs(items.stream()
                .flatMap(item -> item.getTeachers().stream().map(LoadItemTeacher::getTeacherId))
                .collect(Collectors.toSet()));
        int hoursPerPair = settings.hoursPerPair();
        return items.stream()
                .map(item -> new LoadItemDto(item.getId(), item.getSemesterId(), groups.get(item.getGroupId()),
                        disciplines.get(item.getDisciplineId()), item.getLessonKind(), item.getHoursPerWeek(),
                        item.getPlannedHours(), item.isSplitSubgroups(),
                        item.getTeachers().stream()
                                .map(link -> new LoadTeacherDto(link.getSubgroupNumber(), teachers.get(link.getTeacherId())))
                                .toList(),
                        item.getRequiredRoomType(), rooms.get(item.getPreferredRoomId()), item.getControlForm(),
                        item.getSource(), LoadPattern.of(item.getHoursPerWeek(), hoursPerPair), item.getStreamId(),
                        item.getNote(), item.getVersion()))
                .sorted(Comparator.comparing((LoadItemDto dto) -> dto.group().code(), UkrainianOrder.NATURAL)
                        .thenComparing(dto -> dto.discipline().shortName(), UkrainianOrder.NATURAL)
                        .thenComparing(LoadItemDto::splitSubgroups))
                .toList();
    }

    LoadItemDto toDto(LoadItem item) {
        return toDtos(List.of(item)).getFirst();
    }

    private static Set<UUID> ids(Collection<LoadItem> items, java.util.function.Function<LoadItem, UUID> getter) {
        return items.stream().map(getter).filter(Objects::nonNull).collect(Collectors.toSet());
    }
}
