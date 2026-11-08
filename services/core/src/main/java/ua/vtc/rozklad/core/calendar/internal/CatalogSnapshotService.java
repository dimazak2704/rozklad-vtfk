package ua.vtc.rozklad.core.calendar.internal;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.catalog.DisciplineAlias;
import ua.vtc.rozklad.core.catalog.DisciplineAliasRepository;
import ua.vtc.rozklad.core.catalog.DisciplineRepository;
import ua.vtc.rozklad.core.catalog.RoomRepository;
import ua.vtc.rozklad.core.catalog.SpecialtyRepository;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.StudentGroupRepository;
import ua.vtc.rozklad.core.catalog.Subgroup;
import ua.vtc.rozklad.core.catalog.SubgroupRepository;
import ua.vtc.rozklad.core.catalog.TeacherAlias;
import ua.vtc.rozklad.core.catalog.TeacherAliasRepository;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.SemesterDto;

/**
 * {@code GET /api/internal/catalog/snapshot}: everything the importer needs to normalize a file
 * (groups with subgroups, teachers and disciplines with aliases, rooms, specialties) plus the
 * semesters to attach a timetable or load to. Lives in the calendar module because it combines
 * catalog data with semesters; calendar already depends on catalog, the reverse would be a cycle.
 */
@Service
@Transactional(readOnly = true)
public class CatalogSnapshotService {

    private final StudentGroupRepository groups;
    private final SubgroupRepository subgroups;
    private final TeacherRepository teachers;
    private final TeacherAliasRepository teacherAliases;
    private final DisciplineRepository disciplines;
    private final DisciplineAliasRepository disciplineAliases;
    private final RoomRepository rooms;
    private final SpecialtyRepository specialties;
    private final SemesterService semesters;

    public CatalogSnapshotService(StudentGroupRepository groups, SubgroupRepository subgroups,
                                  TeacherRepository teachers, TeacherAliasRepository teacherAliases,
                                  DisciplineRepository disciplines, DisciplineAliasRepository disciplineAliases,
                                  RoomRepository rooms, SpecialtyRepository specialties, SemesterService semesters) {
        this.groups = groups;
        this.subgroups = subgroups;
        this.teachers = teachers;
        this.teacherAliases = teacherAliases;
        this.disciplines = disciplines;
        this.disciplineAliases = disciplineAliases;
        this.rooms = rooms;
        this.specialties = specialties;
        this.semesters = semesters;
    }

    public CatalogSnapshot snapshot() {
        Map<UUID, List<Integer>> subgroupNumbers = subgroups.findAll().stream()
                .sorted(Comparator.comparingInt(Subgroup::getNumber))
                .collect(Collectors.groupingBy(Subgroup::getGroupId,
                        Collectors.mapping(Subgroup::getNumber, Collectors.toList())));
        Map<UUID, List<String>> teacherAliasesById = teacherAliases.findAll().stream()
                .collect(Collectors.groupingBy(TeacherAlias::getTeacherId,
                        Collectors.mapping(TeacherAlias::getAlias, Collectors.toList())));
        Map<UUID, List<String>> disciplineAliasesById = disciplineAliases.findAll().stream()
                .collect(Collectors.groupingBy(DisciplineAlias::getDisciplineId,
                        Collectors.mapping(DisciplineAlias::getAlias, Collectors.toList())));
        return new CatalogSnapshot(
                groups.findAll().stream()
                        .sorted(Comparator.comparingInt(StudentGroup::getCourse).thenComparing(StudentGroup::getCode))
                        .map(group -> new GroupItem(group.getId(), group.getCode(), group.getCourse(),
                                subgroupNumbers.getOrDefault(group.getId(), List.of()), group.isActive()))
                        .toList(),
                teachers.findAll().stream()
                        .map(teacher -> new TeacherItem(teacher.getId(), teacher.getShortName(),
                                teacher.getDisplayName(), teacher.getLastName(),
                                teacherAliasesById.getOrDefault(teacher.getId(), List.of()), teacher.isActive()))
                        .toList(),
                disciplines.findAll().stream()
                        .map(discipline -> new DisciplineItem(discipline.getId(), discipline.getShortName(),
                                discipline.getName(), disciplineAliasesById.getOrDefault(discipline.getId(), List.of()),
                                discipline.isActive()))
                        .toList(),
                rooms.findAll().stream()
                        .map(room -> new RoomItem(room.getId(), room.getName(), room.getType(), room.isActive()))
                        .toList(),
                specialties.findAll().stream()
                        .map(specialty -> new SpecialtyItem(specialty.getId(), specialty.getLetters(), specialty.getName()))
                        .toList(),
                semesters.list());
    }

    /** Contract fields plus additive {@code active} flags and {@code specialties}. */
    public record CatalogSnapshot(List<GroupItem> groups, List<TeacherItem> teachers, List<DisciplineItem> disciplines,
                                  List<RoomItem> rooms, List<SpecialtyItem> specialties, List<SemesterDto> semesters) {
    }

    public record GroupItem(UUID id, String code, int course, List<Integer> subgroups, boolean active) {
    }

    public record TeacherItem(UUID id, String shortName, String displayName, String lastName, List<String> aliases,
                              boolean active) {
    }

    public record DisciplineItem(UUID id, String shortName, String name, List<String> aliases, boolean active) {
    }

    public record RoomItem(UUID id, String name, RoomType type, boolean active) {
    }

    public record SpecialtyItem(UUID id, String letters, String name) {
    }
}
