package ua.vtc.rozklad.core.timetable.internal.support;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.catalog.CatalogQueries;
import ua.vtc.rozklad.core.catalog.DisciplineRef;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.catalog.RoomRef;
import ua.vtc.rozklad.core.catalog.TeacherRef;

/** Loads catalog references for many lessons at once through {@link CatalogQueries} (one query per type). */
@Component
public class CatalogRefs {

    private final CatalogQueries catalog;

    public CatalogRefs(CatalogQueries catalog) {
        this.catalog = catalog;
    }

    public Refs load(Collection<UUID> groupIds, Collection<UUID> disciplineIds, Collection<UUID> teacherIds,
                     Collection<UUID> roomIds) {
        return new Refs(catalog.groupRefs(groupIds), catalog.disciplineRefs(disciplineIds),
                catalog.teacherRefs(teacherIds), catalog.roomRefs(roomIds));
    }

    /** References by id; a missing id maps to {@code null}. */
    public record Refs(Map<UUID, GroupRef> groups, Map<UUID, DisciplineRef> disciplines, Map<UUID, TeacherRef> teachers,
                       Map<UUID, RoomRef> rooms) {

        public GroupRef group(UUID id) {
            return id == null ? null : groups.get(id);
        }

        public DisciplineRef discipline(UUID id) {
            return id == null ? null : disciplines.get(id);
        }

        public TeacherRef teacher(UUID id) {
            return id == null ? null : teachers.get(id);
        }

        public RoomRef room(UUID id) {
            return id == null ? null : rooms.get(id);
        }
    }
}
