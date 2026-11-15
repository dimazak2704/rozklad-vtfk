package ua.vtc.rozklad.importer.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Catalog of core as returned by {@code GET /api/internal/catalog/snapshot} (CONTRACTS §4). The
 * importer only reads it; unknown fields are ignored so core may add more.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogSnapshot(List<GroupItem> groups, List<TeacherItem> teachers, List<DisciplineItem> disciplines,
                              List<RoomItem> rooms, List<SemesterItem> semesters, List<PeriodKindItem> periodKinds) {

    public CatalogSnapshot {
        groups = groups == null ? List.of() : List.copyOf(groups);
        teachers = teachers == null ? List.of() : List.copyOf(teachers);
        disciplines = disciplines == null ? List.of() : List.copyOf(disciplines);
        rooms = rooms == null ? List.of() : List.copyOf(rooms);
        semesters = semesters == null ? List.of() : List.copyOf(semesters);
        periodKinds = periodKinds == null ? List.of() : List.copyOf(periodKinds);
    }

    /** A catalog without period kinds (an older core, or a test that does not need them). */
    public CatalogSnapshot(List<GroupItem> groups, List<TeacherItem> teachers, List<DisciplineItem> disciplines,
                           List<RoomItem> rooms, List<SemesterItem> semesters) {
        this(groups, teachers, disciplines, rooms, semesters, List.of());
    }

    public static CatalogSnapshot empty() {
        return new CatalogSnapshot(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** {@code active} is additive in core's snapshot; {@code null} (an older core) counts as active. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GroupItem(UUID id, String code, Integer course, List<Integer> subgroups, Boolean active) {

        public GroupItem(UUID id, String code, Integer course, List<Integer> subgroups) {
            this(id, code, course, subgroups, null);
        }

        public boolean isActive() {
            return active == null || active;
        }
    }

    /**
     * A period kind of core's catalog (CONTRACTS «period kinds»): {@code effect} DAY_OFF, SUSPEND, ONLINE or
     * INFO; {@code aliases} — other codes of the kind («З» for «ЗУ»); inactive kinds are listed too.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PeriodKindItem(UUID id, String code, String name, String effect, String color, List<String> aliases,
                                 String noteTemplate, Boolean active) {

        public PeriodKindItem {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }

        public boolean isActive() {
            return active == null || active;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TeacherItem(UUID id, String shortName, String displayName, String lastName, List<String> aliases) {

        public TeacherItem {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DisciplineItem(UUID id, String shortName, String name, List<String> aliases) {

        public DisciplineItem {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RoomItem(UUID id, String name, String type) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SemesterItem(UUID id, String name, Integer number, LocalDate startsOn, LocalDate endsOn, Boolean active) {
    }

    public Optional<TeacherItem> teacher(UUID id) {
        return teachers.stream().filter(teacher -> teacher.id().equals(id)).findFirst();
    }

    public Optional<DisciplineItem> discipline(UUID id) {
        return disciplines.stream().filter(discipline -> discipline.id().equals(id)).findFirst();
    }

    public Optional<GroupItem> group(UUID id) {
        return groups.stream().filter(group -> group.id().equals(id)).findFirst();
    }

    public Optional<RoomItem> room(UUID id) {
        return rooms.stream().filter(room -> room.id().equals(id)).findFirst();
    }

    public boolean hasSemester(UUID id) {
        return semesters.stream().anyMatch(semester -> semester.id().equals(id));
    }
}
