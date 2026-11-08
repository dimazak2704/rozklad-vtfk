package ua.vtc.rozklad.core.catalog.internal;

import static ua.vtc.rozklad.core.shared.persistence.EntityGuards.checkVersion;
import static ua.vtc.rozklad.core.shared.persistence.EntityGuards.deleteOrConflict;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.catalog.GroupCodes;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.RoomRef;
import ua.vtc.rozklad.core.catalog.RoomRepository;
import ua.vtc.rozklad.core.catalog.Specialty;
import ua.vtc.rozklad.core.catalog.SpecialtyRepository;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.StudentGroupRepository;
import ua.vtc.rozklad.core.catalog.Subgroup;
import ua.vtc.rozklad.core.catalog.SubgroupRepository;
import ua.vtc.rozklad.core.catalog.SubgroupUsage;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherRef;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.catalog.internal.GroupDtos.GroupDto;
import ua.vtc.rozklad.core.catalog.internal.GroupDtos.GroupRequest;
import ua.vtc.rozklad.core.catalog.internal.GroupDtos.SubgroupDto;
import ua.vtc.rozklad.core.catalog.internal.GroupDtos.SubgroupRequest;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.FieldErrors;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.persistence.Specs;
import ua.vtc.rozklad.core.shared.web.PageQuery;
import ua.vtc.rozklad.core.shared.web.PageResponse;
import ua.vtc.rozklad.core.shared.web.Ref;

/**
 * Групи and підгрупи. The canonical code (DOMAIN §10) carries course, specialty letters and number,
 * so those fields are derived from it and may not contradict it.
 */
@Service
@Transactional(readOnly = true)
public class GroupService {

    static final String CODE_FORMAT = "Код групи має вигляд «4КІ1»: курс, літери спеціальності, номер.";

    private static final Map<String, String> SORTABLE = Map.of("code", "code", "course", "course",
            "shift", "shift", "size", "size", "active", "active");
    private static final Sort DEFAULT_SORT = Sort.by("course", "code");

    private final StudentGroupRepository groups;
    private final SubgroupRepository subgroups;
    private final SpecialtyRepository specialties;
    private final TeacherRepository teachers;
    private final RoomRepository rooms;
    private final List<SubgroupUsage> subgroupUsages;

    public GroupService(StudentGroupRepository groups, SubgroupRepository subgroups, SpecialtyRepository specialties,
                        TeacherRepository teachers, RoomRepository rooms, List<SubgroupUsage> subgroupUsages) {
        this.groups = groups;
        this.subgroups = subgroups;
        this.specialties = specialties;
        this.teachers = teachers;
        this.rooms = rooms;
        this.subgroupUsages = List.copyOf(subgroupUsages);
    }

    public PageResponse<GroupDto> list(String q, Integer course, UUID specialtyId, Integer shift, Boolean active,
                                       PageQuery page) {
        Page<StudentGroup> found = groups.findAll(Specs.<StudentGroup>containsIgnoreCase(q, "code")
                        .and(Specs.equalTo("course", course))
                        .and(Specs.equalTo("specialtyId", specialtyId))
                        .and(Specs.equalTo("shift", shift))
                        .and(Specs.equalTo("active", active)),
                page.toPageable(SORTABLE, DEFAULT_SORT));
        Function<StudentGroup, GroupDto> mapper = mapper(found.getContent());
        return PageResponse.of(found, mapper);
    }

    public GroupDto get(UUID id) {
        StudentGroup group = find(id);
        return mapper(List.of(group)).apply(group);
    }

    @Transactional
    public GroupDto create(GroupRequest request) {
        Resolved resolved = resolve(request, null);
        StudentGroup group = new StudentGroup(resolved.code(), resolved.course(), resolved.specialtyId());
        resolved.copyTo(group, request);
        groups.save(group);
        return mapper(List.of(group)).apply(group);
    }

    @Transactional
    public GroupDto update(UUID id, GroupRequest request) {
        StudentGroup group = find(id);
        checkVersion(group, request.version());
        Resolved resolved = resolve(request, id);
        group.setCode(resolved.code());
        group.setCourse(resolved.course());
        group.setSpecialtyId(resolved.specialtyId());
        resolved.copyTo(group, request);
        groups.flush();
        return mapper(List.of(group)).apply(group);
    }

    @Transactional
    public void delete(UUID id) {
        StudentGroup group = find(id);
        deleteOrConflict(groups, group, () -> ("Групу «%s» не можна видалити: вона є в розкладі, навантаженні або "
                + "замінах. Позначте її неактивною.").formatted(group.getCode()));
    }

    public List<SubgroupDto> subgroups(UUID groupId) {
        find(groupId);
        return subgroups.findAllByGroupIdOrderByNumber(groupId).stream().map(SubgroupDto::of).toList();
    }

    @Transactional
    public SubgroupDto addSubgroup(UUID groupId, SubgroupRequest request) {
        StudentGroup group = find(groupId);
        subgroups.findByGroupIdAndNumber(groupId, request.number()).ifPresent(existing -> {
            throw new ConflictException("У групі %s уже є підгрупа %d.".formatted(group.getCode(), request.number()));
        });
        Subgroup subgroup = new Subgroup(groupId, request.number());
        subgroup.setName(DepartmentService.blankToNull(request.name()));
        subgroup.setSize(request.size());
        return SubgroupDto.of(subgroups.save(subgroup));
    }

    @Transactional
    public SubgroupDto updateSubgroup(UUID groupId, UUID subgroupId, SubgroupRequest request) {
        StudentGroup group = find(groupId);
        Subgroup subgroup = findSubgroup(groupId, subgroupId);
        checkVersion(subgroup, request.version());
        if (request.number() != subgroup.getNumber()) {
            ensureUnused(group, subgroup.getNumber());
            subgroups.findByGroupIdAndNumber(groupId, request.number()).ifPresent(existing -> {
                throw new ConflictException("У групі %s уже є підгрупа %d.".formatted(group.getCode(), request.number()));
            });
            subgroup.setNumber(request.number());
        }
        subgroup.setName(DepartmentService.blankToNull(request.name()));
        subgroup.setSize(request.size());
        subgroups.flush();
        return SubgroupDto.of(subgroup);
    }

    @Transactional
    public void deleteSubgroup(UUID groupId, UUID subgroupId) {
        StudentGroup group = find(groupId);
        Subgroup subgroup = findSubgroup(groupId, subgroupId);
        ensureUnused(group, subgroup.getNumber());
        subgroups.delete(subgroup);
    }

    public StudentGroup find(UUID id) {
        return groups.findById(id).orElseThrow(() -> NotFoundException.of("Групу", id));
    }

    private Subgroup findSubgroup(UUID groupId, UUID subgroupId) {
        return subgroups.findById(subgroupId)
                .filter(found -> found.getGroupId().equals(groupId))
                .orElseThrow(() -> NotFoundException.of("Підгрупу", subgroupId));
    }

    /** Subgroups have no foreign keys (rows store the number), so the modules using them are asked. */
    private void ensureUnused(StudentGroup group, int number) {
        List<String> usages = subgroupUsages.stream()
                .flatMap(usage -> usage.usagesOf(group.getId(), number).stream())
                .toList();
        if (!usages.isEmpty()) {
            throw new ConflictException("Підгрупу %d групи %s використано: %s.".formatted(number, group.getCode(),
                    String.join("; ", usages)));
        }
    }

    private Resolved resolve(GroupRequest request, UUID ownId) {
        FieldErrors errors = new FieldErrors();
        Optional<GroupCodes.GroupCode> parsed = GroupCodes.canonical(request.code()).flatMap(GroupCodes::parse);
        if (parsed.isEmpty()) {
            errors.add("code", CODE_FORMAT);
            errors.throwIfAny();
        }
        GroupCodes.GroupCode code = parsed.get();
        if (code.course() < 1 || code.course() > 6) {
            errors.add("code", "Курс у коді групи має бути від 1 до 6.");
        }
        if (request.course() != null && request.course() != code.course()) {
            errors.add("course", "Курс не збігається з кодом групи (%d).".formatted(code.course()));
        }
        if (request.number() != null && !request.number().equals(code.number())) {
            errors.add("number", "Номер не збігається з кодом групи.");
        }
        UUID specialtyId = resolveSpecialty(request.specialtyId(), code.letters(), errors);
        if (request.curatorTeacherId() != null && !teachers.existsById(request.curatorTeacherId())) {
            errors.add("curatorTeacherId", "Викладача не знайдено.");
        }
        if (request.homeRoomId() != null && !rooms.existsById(request.homeRoomId())) {
            errors.add("homeRoomId", "Аудиторію не знайдено.");
        }
        errors.throwIfAny();
        groups.findByCode(code.value())
                .filter(existing -> !existing.getId().equals(ownId))
                .ifPresent(existing -> {
                    throw new ConflictException("Група %s уже існує.".formatted(existing.getCode()));
                });
        return new Resolved(code.value(), code.course(), code.number(), specialtyId);
    }

    private UUID resolveSpecialty(UUID requestedId, String letters, FieldErrors errors) {
        if (requestedId != null) {
            Optional<Specialty> specialty = specialties.findById(requestedId);
            if (specialty.isEmpty()) {
                errors.add("specialtyId", "Спеціальність не знайдено.");
                return null;
            }
            if (!specialty.get().getLetters().equals(letters)) {
                errors.add("specialtyId", "Літери спеціальності (%s) не збігаються з кодом групи (%s)."
                        .formatted(specialty.get().getLetters(), letters));
            }
            return requestedId;
        }
        return specialties.findByLetters(letters).map(Specialty::getId).orElseGet(() -> {
            errors.add("code", "Спеціальність «%s» не знайдено: спершу додайте її до довідника.".formatted(letters));
            return null;
        });
    }

    /** Builds DTOs for a page of groups with one query per referenced table. */
    private Function<StudentGroup, GroupDto> mapper(Collection<StudentGroup> page) {
        Set<UUID> groupIds = page.stream().map(StudentGroup::getId).collect(Collectors.toSet());
        Map<UUID, List<SubgroupDto>> subgroupsByGroup = subgroups.findAllByGroupIdIn(groupIds).stream()
                .sorted(java.util.Comparator.comparingInt(Subgroup::getNumber))
                .collect(Collectors.groupingBy(Subgroup::getGroupId,
                        Collectors.mapping(SubgroupDto::of, Collectors.toList())));
        Map<UUID, Specialty> specialtiesById = byId(specialties.findAllById(ids(page, StudentGroup::getSpecialtyId)),
                Specialty::getId);
        Map<UUID, Teacher> curators = byId(teachers.findAllById(ids(page, StudentGroup::getCuratorTeacherId)),
                Teacher::getId);
        Map<UUID, Room> homeRooms = byId(rooms.findAllById(ids(page, StudentGroup::getHomeRoomId)), Room::getId);
        return group -> {
            Specialty specialty = specialtiesById.get(group.getSpecialtyId());
            Teacher curator = curators.get(group.getCuratorTeacherId());
            Room homeRoom = homeRooms.get(group.getHomeRoomId());
            return new GroupDto(group.getId(), group.getCode(), group.getCourse(), group.getSpecialtyId(),
                    specialty == null ? null : new Ref(specialty.getId(), specialty.getLetters()),
                    group.getNumber(), group.getSize(), group.getShift(),
                    curator == null ? null : TeacherRef.of(curator),
                    homeRoom == null ? null : RoomRef.of(homeRoom),
                    group.isActive(), subgroupsByGroup.getOrDefault(group.getId(), List.of()), group.getVersion());
        };
    }

    private static <T> Set<UUID> ids(Collection<T> items, Function<T, UUID> getter) {
        return items.stream().map(getter).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private static <T> Map<UUID, T> byId(Collection<T> items, Function<T, UUID> getter) {
        return items.stream().collect(Collectors.toMap(getter, Function.identity()));
    }

    /** Values derived from the canonical code plus defaults. */
    private record Resolved(String code, int course, Integer number, UUID specialtyId) {

        void copyTo(StudentGroup group, GroupRequest request) {
            group.setNumber(number);
            group.setSize(request.size());
            group.setShift(request.shift() != null ? request.shift() : defaultShift(course));
            group.setCuratorTeacherId(request.curatorTeacherId());
            group.setHomeRoomId(request.homeRoomId());
            group.setActive(request.active() == null || request.active());
        }
    }

    /** DOMAIN §2: in the real schedule the 2nd course studies in the second shift. */
    static int defaultShift(int course) {
        return course == 2 ? 2 : 1;
    }
}
