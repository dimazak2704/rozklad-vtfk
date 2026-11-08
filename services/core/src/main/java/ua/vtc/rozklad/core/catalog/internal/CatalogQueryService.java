package ua.vtc.rozklad.core.catalog.internal;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.catalog.CatalogQueries;
import ua.vtc.rozklad.core.catalog.CatalogText;
import ua.vtc.rozklad.core.catalog.DisciplineAliasRepository;
import ua.vtc.rozklad.core.catalog.DisciplineRef;
import ua.vtc.rozklad.core.catalog.DisciplineRepository;
import ua.vtc.rozklad.core.catalog.GroupCodes;
import ua.vtc.rozklad.core.catalog.GroupInfo;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.catalog.RoomInfo;
import ua.vtc.rozklad.core.catalog.RoomRef;
import ua.vtc.rozklad.core.catalog.RoomRepository;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.StudentGroupRepository;
import ua.vtc.rozklad.core.catalog.Subgroup;
import ua.vtc.rozklad.core.catalog.SubgroupRepository;
import ua.vtc.rozklad.core.catalog.TeacherAliasRepository;
import ua.vtc.rozklad.core.catalog.TeacherDiscipline;
import ua.vtc.rozklad.core.catalog.TeacherDisciplineRepository;
import ua.vtc.rozklad.core.catalog.TeacherInfo;
import ua.vtc.rozklad.core.catalog.TeacherRef;
import ua.vtc.rozklad.core.catalog.TeacherRepository;

/** Read API of the catalog for other modules. */
@Service
@Transactional(readOnly = true)
class CatalogQueryService implements CatalogQueries {

    private final StudentGroupRepository groups;
    private final SubgroupRepository subgroups;
    private final TeacherRepository teachers;
    private final TeacherAliasRepository teacherAliases;
    private final DisciplineRepository disciplines;
    private final DisciplineAliasRepository disciplineAliases;
    private final RoomRepository rooms;
    private final TeacherDisciplineRepository competences;

    CatalogQueryService(StudentGroupRepository groups, SubgroupRepository subgroups, TeacherRepository teachers,
                        TeacherAliasRepository teacherAliases, DisciplineRepository disciplines,
                        DisciplineAliasRepository disciplineAliases, RoomRepository rooms,
                        TeacherDisciplineRepository competences) {
        this.groups = groups;
        this.subgroups = subgroups;
        this.teachers = teachers;
        this.teacherAliases = teacherAliases;
        this.disciplines = disciplines;
        this.disciplineAliases = disciplineAliases;
        this.rooms = rooms;
        this.competences = competences;
    }

    @Override
    public Map<UUID, GroupRef> groupRefs(Collection<UUID> ids) {
        return toMap(groups.findAllById(present(ids)), StudentGroup::getId, GroupRef::of);
    }

    @Override
    public Map<UUID, TeacherRef> teacherRefs(Collection<UUID> ids) {
        return toMap(teachers.findAllById(present(ids)), teacher -> teacher.getId(), TeacherRef::of);
    }

    @Override
    public Map<UUID, DisciplineRef> disciplineRefs(Collection<UUID> ids) {
        return toMap(disciplines.findAllById(present(ids)), discipline -> discipline.getId(), DisciplineRef::of);
    }

    @Override
    public Map<UUID, RoomRef> roomRefs(Collection<UUID> ids) {
        return toMap(rooms.findAllById(present(ids)), room -> room.getId(), RoomRef::of);
    }

    @Override
    public Optional<GroupInfo> group(UUID id) {
        return groups.findById(id).map(group -> GroupInfo.of(group, subgroupNumbers(group.getId())));
    }

    @Override
    public Map<UUID, GroupInfo> groups(Collection<UUID> ids) {
        List<StudentGroup> found = groups.findAllById(present(ids));
        return infos(found).stream().collect(Collectors.toMap(GroupInfo::id, Function.identity()));
    }

    @Override
    public List<GroupInfo> activeGroups() {
        return infos(groups.findAllByActiveTrueOrderByCourseAscCodeAsc());
    }

    @Override
    public Optional<TeacherInfo> teacher(UUID id) {
        return teachers.findById(id).map(TeacherInfo::of);
    }

    @Override
    public Optional<RoomInfo> room(UUID id) {
        return rooms.findById(id).map(RoomInfo::of);
    }

    @Override
    public Optional<DisciplineRef> discipline(UUID id) {
        return disciplines.findById(id).map(DisciplineRef::of);
    }

    @Override
    public Optional<GroupInfo> groupByCode(String code) {
        return GroupCodes.canonical(code)
                .flatMap(groups::findByCode)
                .map(group -> GroupInfo.of(group, subgroupNumbers(group.getId())));
    }

    @Override
    public Optional<TeacherInfo> teacherByShortName(String shortName) {
        String key = CatalogText.key(shortName);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        return teachers.findByShortName(CatalogText.clean(shortName))
                .or(() -> teachers.findAll().stream()
                        .filter(teacher -> CatalogText.key(teacher.getShortName()).equals(key))
                        .findFirst())
                .or(() -> teacherAliases.findByNormalized(key).flatMap(alias -> teachers.findById(alias.getTeacherId())))
                .map(TeacherInfo::of);
    }

    @Override
    public Optional<DisciplineRef> disciplineByShortName(String shortName) {
        String key = CatalogText.key(shortName);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        return disciplines.findByShortName(CatalogText.clean(shortName))
                .or(() -> disciplines.findAll().stream()
                        .filter(discipline -> CatalogText.key(discipline.getShortName()).equals(key))
                        .findFirst())
                .or(() -> disciplineAliases.findByNormalized(key)
                        .flatMap(alias -> disciplines.findById(alias.getDisciplineId())))
                .map(DisciplineRef::of);
    }

    @Override
    public Optional<RoomInfo> roomByName(String name) {
        String key = CatalogText.key(name);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        return rooms.findByName(CatalogText.clean(name))
                .or(() -> rooms.findAll().stream().filter(room -> CatalogText.key(room.getName()).equals(key)).findFirst())
                .map(RoomInfo::of);
    }

    @Override
    public Optional<TeacherInfo> teacherByAccount(UUID keycloakUserId) {
        return teachers.findByKeycloakUserId(keycloakUserId).map(TeacherInfo::of);
    }

    @Override
    public boolean hasCompetence(UUID teacherId, UUID disciplineId) {
        return competences.existsByTeacherIdAndDisciplineId(teacherId, disciplineId);
    }

    @Override
    public Set<UUID> teachersCompetentIn(UUID disciplineId) {
        return competences.findAllByDisciplineId(disciplineId).stream()
                .map(TeacherDiscipline::getTeacherId)
                .collect(Collectors.toUnmodifiableSet());
    }

    private List<Integer> subgroupNumbers(UUID groupId) {
        return subgroups.findAllByGroupIdOrderByNumber(groupId).stream().map(Subgroup::getNumber).toList();
    }

    private List<GroupInfo> infos(List<StudentGroup> list) {
        Map<UUID, List<Integer>> numbers = subgroups.findAllByGroupIdIn(
                        list.stream().map(StudentGroup::getId).collect(Collectors.toSet())).stream()
                .sorted(Comparator.comparingInt(Subgroup::getNumber))
                .collect(Collectors.groupingBy(Subgroup::getGroupId,
                        Collectors.mapping(Subgroup::getNumber, Collectors.toList())));
        return list.stream().map(group -> GroupInfo.of(group, numbers.getOrDefault(group.getId(), List.of()))).toList();
    }

    /**
     * Read-only, but {@code get(null)} answers {@code null}: callers look up nullable references
     * (a load item without a preferred room) and immutable {@code Map.of}-style maps would throw.
     */
    private static <E, R> Map<UUID, R> toMap(List<E> entities, Function<E, UUID> id, Function<E, R> mapper) {
        Map<UUID, R> result = new HashMap<>();
        entities.forEach(entity -> result.put(id.apply(entity), mapper.apply(entity)));
        return Collections.unmodifiableMap(result);
    }

    /** Distinct non-null ids: callers pass optional references as they are. */
    private static Set<UUID> present(Collection<UUID> ids) {
        return ids.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
    }
}
