package ua.vtc.rozklad.core.catalog.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriUtils;
import ua.vtc.rozklad.core.catalog.Building;
import ua.vtc.rozklad.core.catalog.BuildingRepository;
import ua.vtc.rozklad.core.catalog.CatalogLabels;
import ua.vtc.rozklad.core.catalog.CycleCommission;
import ua.vtc.rozklad.core.catalog.CycleCommissionRepository;
import ua.vtc.rozklad.core.catalog.Department;
import ua.vtc.rozklad.core.catalog.DepartmentRepository;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.RoomRepository;
import ua.vtc.rozklad.core.catalog.Specialty;
import ua.vtc.rozklad.core.catalog.SpecialtyRepository;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.StudentGroupRepository;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherAlias;
import ua.vtc.rozklad.core.catalog.TeacherAliasRepository;
import ua.vtc.rozklad.core.catalog.TeacherNames;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.catalog.UkrainianOrder;
import ua.vtc.rozklad.core.catalog.internal.PublicCatalogDtos.PublicGroupDto;
import ua.vtc.rozklad.core.catalog.internal.PublicCatalogDtos.PublicRoomDto;
import ua.vtc.rozklad.core.catalog.internal.PublicCatalogDtos.PublicTeacherDto;
import ua.vtc.rozklad.core.catalog.internal.PublicCatalogDtos.SearchResultDto;
import ua.vtc.rozklad.core.catalog.internal.PublicCatalogDtos.SearchResultType;
import ua.vtc.rozklad.core.catalog.internal.PublicCatalogDtos.SpecialtyView;
import ua.vtc.rozklad.core.settings.SettingsService;

/**
 * Anonymous catalog views: lists of active groups, teachers and rooms and the global fuzzy
 * search (UI.md «wow» 3). Only short teacher names are public unless the setting
 * {@code showTeacherFullNames} is on.
 */
@Service
@Transactional(readOnly = true)
public class PublicCatalogService {

    private static final List<SearchResultType> TYPE_ORDER = List.of(SearchResultType.GROUP,
            SearchResultType.TEACHER, SearchResultType.ROOM);

    private final StudentGroupRepository groups;
    private final SpecialtyRepository specialties;
    private final DepartmentRepository departments;
    private final TeacherRepository teachers;
    private final TeacherAliasRepository teacherAliases;
    private final CycleCommissionRepository commissions;
    private final RoomRepository rooms;
    private final BuildingRepository buildings;
    private final SettingsService settings;

    public PublicCatalogService(StudentGroupRepository groups, SpecialtyRepository specialties,
                                DepartmentRepository departments, TeacherRepository teachers,
                                TeacherAliasRepository teacherAliases, CycleCommissionRepository commissions,
                                RoomRepository rooms, BuildingRepository buildings, SettingsService settings) {
        this.groups = groups;
        this.specialties = specialties;
        this.departments = departments;
        this.teachers = teachers;
        this.teacherAliases = teacherAliases;
        this.commissions = commissions;
        this.rooms = rooms;
        this.buildings = buildings;
        this.settings = settings;
    }

    public List<PublicGroupDto> groups() {
        Map<UUID, Specialty> specialtiesById = byId(specialties.findAll(), Specialty::getId);
        Map<UUID, Department> departmentsById = byId(departments.findAll(), Department::getId);
        return groups.findAllByActiveTrueOrderByCourseAscCodeAsc().stream()
                .sorted(Comparator.comparingInt(StudentGroup::getCourse)
                        .thenComparing(StudentGroup::getCode, UkrainianOrder.NATURAL))
                .map(group -> {
                    Specialty specialty = specialtiesById.get(group.getSpecialtyId());
                    Department department = specialty == null || specialty.getDepartmentId() == null ? null
                            : departmentsById.get(specialty.getDepartmentId());
                    return new PublicGroupDto(group.getId(), group.getCode(), group.getCourse(),
                            specialty == null ? null : new SpecialtyView(specialty.getLetters(), specialty.getName()),
                            department == null ? null : department.getName(), group.getShift());
                })
                .toList();
    }

    public List<PublicTeacherDto> teachers() {
        boolean fullNames = settings.showTeacherFullNames();
        Map<UUID, CycleCommission> commissionsById = byId(commissions.findAll(), CycleCommission::getId);
        return teachers.findAllByActiveTrueOrderByShortName().stream()
                .sorted(Comparator.comparing(Teacher::getShortName, UkrainianOrder.NATURAL))
                .map(teacher -> {
                    CycleCommission commission = commissionsById.get(teacher.getCycleCommissionId());
                    return new PublicTeacherDto(teacher.getId(), teacher.getShortName(), teacher.getDisplayName(),
                            commission == null ? null : commission.getName(), fullNameOrNull(teacher, fullNames));
                })
                .toList();
    }

    public List<PublicRoomDto> rooms() {
        Map<UUID, Building> buildingsById = byId(buildings.findAll(), Building::getId);
        return rooms.findAll().stream()
                .filter(Room::isActive)
                .sorted(Comparator.comparing(Room::getName, UkrainianOrder.NATURAL))
                .map(room -> {
                    Building building = buildingsById.get(room.getBuildingId());
                    return new PublicRoomDto(room.getId(), room.getName(), room.getType(), room.getCapacity(),
                            building == null ? null : building.getName());
                })
                .toList();
    }

    /**
     * Ranked results across groups, teachers and rooms: higher score first (exact &gt; prefix &gt;
     * fuzzy), then groups before teachers before rooms, then label.
     */
    public List<SearchResultDto> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<String> variants = SearchMatcher.queryVariants(query.strip());
        if (variants.isEmpty()) {
            return List.of();
        }
        List<Scored> scored = new ArrayList<>();
        scoreGroups(variants, scored);
        scoreTeachers(variants, scored);
        scoreRooms(variants, scored);
        return scored.stream()
                .sorted(Comparator.comparingInt(Scored::score).reversed()
                        .thenComparingInt(result -> TYPE_ORDER.indexOf(result.dto().type()))
                        .thenComparing(result -> result.dto().label(), UkrainianOrder.NATURAL))
                .limit(limit)
                .map(Scored::dto)
                .toList();
    }

    private void scoreGroups(List<String> variants, List<Scored> out) {
        Map<UUID, Specialty> specialtiesById = byId(specialties.findAll(), Specialty::getId);
        for (StudentGroup group : groups.findAllByActiveTrueOrderByCourseAscCodeAsc()) {
            int score = SearchMatcher.score(variants, List.of(group.getCode()));
            if (score >= SearchMatcher.MIN_SCORE) {
                Specialty specialty = specialtiesById.get(group.getSpecialtyId());
                String sublabel = CatalogLabels.course(group.getCourse())
                        + (specialty == null || specialty.getName().equals(specialty.getLetters())
                        ? "" : " · " + specialty.getName());
                out.add(new Scored(score, new SearchResultDto(SearchResultType.GROUP, group.getId(), group.getCode(),
                        sublabel, "/g/" + UriUtils.encodePathSegment(group.getCode(), StandardCharsets.UTF_8))));
            }
        }
    }

    private void scoreTeachers(List<String> variants, List<Scored> out) {
        boolean fullNames = settings.showTeacherFullNames();
        Map<UUID, CycleCommission> commissionsById = byId(commissions.findAll(), CycleCommission::getId);
        Map<UUID, List<String>> aliasesByTeacher = teacherAliases.findAll().stream()
                .collect(Collectors.groupingBy(TeacherAlias::getTeacherId,
                        Collectors.mapping(TeacherAlias::getAlias, Collectors.toList())));
        for (Teacher teacher : teachers.findAllByActiveTrueOrderByShortName()) {
            String fullName = fullNameOrNull(teacher, fullNames);
            List<String> candidates = Stream.concat(
                            Stream.of(teacher.getShortName(), teacher.getDisplayName(), teacher.getLastName(), fullName),
                            aliasesByTeacher.getOrDefault(teacher.getId(), List.of()).stream())
                    .filter(Objects::nonNull)
                    .toList();
            int score = SearchMatcher.score(variants, candidates);
            if (score >= SearchMatcher.MIN_SCORE) {
                CycleCommission commission = commissionsById.get(teacher.getCycleCommissionId());
                String sublabel = fullName != null ? fullName : commission == null ? "Викладач" : commission.getName();
                out.add(new Scored(score, new SearchResultDto(SearchResultType.TEACHER, teacher.getId(),
                        teacher.getShortName(), sublabel, "/t/" + teacher.getId())));
            }
        }
    }

    private void scoreRooms(List<String> variants, List<Scored> out) {
        Map<UUID, Building> buildingsById = byId(buildings.findAll(), Building::getId);
        for (Room room : rooms.findAll()) {
            if (!room.isActive()) {
                continue;
            }
            String label = CatalogLabels.room(room.getName());
            int score = SearchMatcher.score(variants, List.of(room.getName(), label));
            if (score >= SearchMatcher.MIN_SCORE) {
                Building building = buildingsById.get(room.getBuildingId());
                String sublabel = CatalogLabels.roomType(room.getType())
                        + (building == null ? "" : " · " + building.getName());
                out.add(new Scored(score, new SearchResultDto(SearchResultType.ROOM, room.getId(), label, sublabel,
                        "/r/" + room.getId())));
            }
        }
    }

    private static String fullNameOrNull(Teacher teacher, boolean allowed) {
        if (!allowed || teacher.getFirstName() == null) {
            return null;
        }
        return TeacherNames.fullName(teacher.getLastName(), teacher.getFirstName(), teacher.getMiddleName(),
                teacher.getShortName());
    }

    private static <T> Map<UUID, T> byId(List<T> items, Function<T, UUID> id) {
        return items.stream().collect(Collectors.toMap(id, Function.identity()));
    }

    private record Scored(int score, SearchResultDto dto) {
    }
}
