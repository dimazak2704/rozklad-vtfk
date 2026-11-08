package ua.vtc.rozklad.core.catalog.internal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.catalog.Building;
import ua.vtc.rozklad.core.catalog.BuildingRepository;
import ua.vtc.rozklad.core.catalog.CatalogImported;
import ua.vtc.rozklad.core.catalog.CatalogText;
import ua.vtc.rozklad.core.catalog.CycleCommission;
import ua.vtc.rozklad.core.catalog.CycleCommissionRepository;
import ua.vtc.rozklad.core.catalog.Department;
import ua.vtc.rozklad.core.catalog.DepartmentRepository;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.DisciplineAlias;
import ua.vtc.rozklad.core.catalog.DisciplineAliasRepository;
import ua.vtc.rozklad.core.catalog.DisciplineRepository;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.RoomRepository;
import ua.vtc.rozklad.core.catalog.Specialty;
import ua.vtc.rozklad.core.catalog.SpecialtyRepository;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.StudentGroupRepository;
import ua.vtc.rozklad.core.catalog.Subgroup;
import ua.vtc.rozklad.core.catalog.SubgroupRepository;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherAlias;
import ua.vtc.rozklad.core.catalog.TeacherAliasRepository;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.catalog.internal.CatalogImportDtos.CatalogImportRequest;
import ua.vtc.rozklad.core.catalog.internal.CatalogImportDtos.CatalogImportResult;

/**
 * Applies a catalog import (CONTRACTS §2 «Import apply») in one transaction: sections are upserted
 * parents first (departments → specialties → commissions/buildings → teachers, disciplines, rooms →
 * groups → aliases), every problem is collected as a field error and the whole import is rejected
 * when there is at least one. On success {@link CatalogImported} is published.
 */
@Service
public class CatalogImportService {

    private final DepartmentRepository departments;
    private final SpecialtyRepository specialties;
    private final CycleCommissionRepository commissions;
    private final BuildingRepository buildings;
    private final TeacherRepository teachers;
    private final TeacherAliasRepository teacherAliases;
    private final DisciplineRepository disciplines;
    private final DisciplineAliasRepository disciplineAliases;
    private final RoomRepository rooms;
    private final StudentGroupRepository groups;
    private final SubgroupRepository subgroups;
    private final ApplicationEventPublisher events;

    public CatalogImportService(DepartmentRepository departments, SpecialtyRepository specialties,
                                CycleCommissionRepository commissions, BuildingRepository buildings,
                                TeacherRepository teachers, TeacherAliasRepository teacherAliases,
                                DisciplineRepository disciplines, DisciplineAliasRepository disciplineAliases,
                                RoomRepository rooms, StudentGroupRepository groups, SubgroupRepository subgroups,
                                ApplicationEventPublisher events) {
        this.departments = departments;
        this.specialties = specialties;
        this.commissions = commissions;
        this.buildings = buildings;
        this.teachers = teachers;
        this.teacherAliases = teacherAliases;
        this.disciplines = disciplines;
        this.disciplineAliases = disciplineAliases;
        this.rooms = rooms;
        this.groups = groups;
        this.subgroups = subgroups;
        this.events = events;
    }

    @Transactional
    public CatalogImportResult apply(CatalogImportRequest request) {
        CatalogImportRun run = new CatalogImportRun(snapshot(), new Writer());
        run.departments(request.departments());
        run.specialties(request.specialties());
        run.cycleCommissions(request.cycleCommissions());
        run.buildings(request.buildings());
        run.teachers(request.teachers());
        run.disciplines(request.disciplines());
        run.rooms(request.rooms());
        run.groups(request.groups());
        run.aliases(request.aliases());
        CatalogImportResult result = run.finish();
        events.publishEvent(new CatalogImported(result.created(), result.updated(),
                List.copyOf(run.importedGroupCodes())));
        return result;
    }

    private CatalogImportRun.Snapshot snapshot() {
        return new CatalogImportRun.Snapshot(
                byKey(departments.findAll(), department -> department.getName().toLowerCase(CatalogText.UKRAINIAN)),
                byKey(specialties.findAll(), Specialty::getLetters),
                byKey(commissions.findAll(), commission -> commission.getName().toLowerCase(CatalogText.UKRAINIAN)),
                byKey(buildings.findAll(), building -> building.getName().toLowerCase(CatalogText.UKRAINIAN)),
                byKey(teachers.findAll(), teacher -> CatalogText.key(teacher.getShortName())),
                byKey(teacherAliases.findAll(), TeacherAlias::getNormalized),
                byKey(disciplines.findAll(), discipline -> CatalogText.key(discipline.getShortName())),
                byKey(disciplineAliases.findAll(), DisciplineAlias::getNormalized),
                byKey(rooms.findAll(), room -> CatalogText.key(room.getName())),
                byKey(groups.findAll(), StudentGroup::getCode),
                subgroups.findAll().stream().collect(Collectors.groupingBy(Subgroup::getGroupId, HashMap::new,
                        Collectors.mapping(Subgroup::getNumber, Collectors.toCollection(HashSet::new)))));
    }

    private static <T> Map<String, T> byKey(List<T> items, Function<T, String> key) {
        Map<String, T> map = new HashMap<>();
        items.forEach(item -> map.putIfAbsent(key.apply(item), item));
        return map;
    }

    /** Persists new records in the order the run creates them (parents before children). */
    final class Writer {

        Department save(Department department) {
            return departments.save(department);
        }

        Specialty save(Specialty specialty) {
            return specialties.save(specialty);
        }

        CycleCommission save(CycleCommission commission) {
            return commissions.save(commission);
        }

        Building save(Building building) {
            return buildings.save(building);
        }

        Teacher save(Teacher teacher) {
            return teachers.save(teacher);
        }

        Discipline save(Discipline discipline) {
            return disciplines.save(discipline);
        }

        Room save(Room room) {
            return rooms.save(room);
        }

        StudentGroup save(StudentGroup group) {
            return groups.save(group);
        }

        void save(Subgroup subgroup) {
            subgroups.save(subgroup);
        }

        void save(TeacherAlias alias) {
            teacherAliases.save(alias);
        }

        void save(DisciplineAlias alias) {
            disciplineAliases.save(alias);
        }

        Optional<Teacher> teacher(java.util.UUID id) {
            return teachers.findById(id);
        }

        Optional<Discipline> discipline(java.util.UUID id) {
            return disciplines.findById(id);
        }
    }
}
