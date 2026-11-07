package ua.vtc.rozklad.core.timetable.internal.importing;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.DisciplineAlias;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherAlias;

/**
 * In-memory index of catalog names for one import: exact names first, then normalized keys of
 * names and aliases (DOMAIN.md §10). A key shared by two entities is ambiguous and never guessed
 * («Поліщук Л.» and «Поліщук М.» are different people).
 */
final class CatalogNameIndex {

    private final Names groups;
    private final Names teachers;
    private final Names disciplines;
    private final Names disciplinesByFullName;
    private final Names rooms;

    private CatalogNameIndex(Names groups, Names teachers, Names disciplines, Names disciplinesByFullName, Names rooms) {
        this.groups = groups;
        this.teachers = teachers;
        this.disciplines = disciplines;
        this.disciplinesByFullName = disciplinesByFullName;
        this.rooms = rooms;
    }

    static CatalogNameIndex build(Collection<StudentGroup> groups, Collection<Teacher> teachers,
                                  Collection<TeacherAlias> teacherAliases, Collection<Discipline> disciplines,
                                  Collection<DisciplineAlias> disciplineAliases, Collection<Room> rooms) {
        Names groupNames = new Names(NameKeys::groupKey);
        groups.forEach(group -> groupNames.add(group.getCode(), group.getId()));

        Names teacherNames = new Names(NameKeys::key);
        teachers.forEach(teacher -> teacherNames.add(teacher.getShortName(), teacher.getId()));
        teacherAliases.forEach(alias -> teacherNames.addAlias(alias.getAlias(), alias.getNormalized(),
                alias.getTeacherId()));

        Names disciplineNames = new Names(NameKeys::key);
        disciplines.forEach(discipline -> disciplineNames.add(discipline.getShortName(), discipline.getId()));
        disciplineAliases.forEach(alias -> disciplineNames.addAlias(alias.getAlias(), alias.getNormalized(),
                alias.getDisciplineId()));
        Names fullNames = new Names(NameKeys::key);
        disciplines.forEach(discipline -> fullNames.add(discipline.getName(), discipline.getId()));

        Names roomNames = new Names(NameKeys::key);
        rooms.forEach(room -> roomNames.add(room.getName(), room.getId()));
        return new CatalogNameIndex(groupNames, teacherNames, disciplineNames, fullNames, roomNames);
    }

    Resolution group(String raw) {
        return groups.resolve(raw);
    }

    Resolution teacher(String raw) {
        return teachers.resolve(raw);
    }

    Resolution discipline(String raw) {
        Resolution byShortName = disciplines.resolve(raw);
        return byShortName instanceof Resolution.Missing ? disciplinesByFullName.resolve(raw) : byShortName;
    }

    Resolution room(String raw) {
        return rooms.resolve(raw);
    }

    /** Result of matching one raw name. */
    sealed interface Resolution {

        record Found(UUID id) implements Resolution {
        }

        record Missing() implements Resolution {
        }

        record Ambiguous(Set<UUID> candidates) implements Resolution {
        }
    }

    private static final class Names {

        private final Function<String, String> keyOf;
        private final Map<String, Set<UUID>> exact = new HashMap<>();
        private final Map<String, Set<UUID>> byKey = new HashMap<>();

        Names(Function<String, String> keyOf) {
            this.keyOf = keyOf;
        }

        void add(String name, UUID id) {
            exact.computeIfAbsent(name.strip(), ignored -> new LinkedHashSet<>()).add(id);
            byKey.computeIfAbsent(keyOf.apply(name), ignored -> new LinkedHashSet<>()).add(id);
        }

        /** An alias is matched by its own spelling and by the normalized form stored in the catalog. */
        void addAlias(String alias, String storedNormalized, UUID id) {
            add(alias, id);
            byKey.computeIfAbsent(storedNormalized, ignored -> new LinkedHashSet<>()).add(id);
        }

        Resolution resolve(String raw) {
            if (raw == null || raw.isBlank()) {
                return new Resolution.Missing();
            }
            Set<UUID> found = exact.get(raw.strip());
            if (found == null || found.isEmpty()) {
                found = byKey.get(keyOf.apply(raw));
            }
            if (found == null || found.isEmpty()) {
                return new Resolution.Missing();
            }
            return found.size() == 1 ? new Resolution.Found(found.iterator().next())
                    : new Resolution.Ambiguous(Set.copyOf(found));
        }
    }
}
