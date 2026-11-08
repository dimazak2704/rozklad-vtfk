package ua.vtc.rozklad.core.catalog.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.catalog.Building;
import ua.vtc.rozklad.core.catalog.CatalogText;
import ua.vtc.rozklad.core.catalog.CycleCommission;
import ua.vtc.rozklad.core.catalog.Department;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.DisciplineAlias;
import ua.vtc.rozklad.core.catalog.Employment;
import ua.vtc.rozklad.core.catalog.GroupCodes;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.Specialty;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.Subgroup;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherAlias;
import ua.vtc.rozklad.core.catalog.TeacherNames;
import ua.vtc.rozklad.core.catalog.internal.CatalogImportDtos.AliasItem;
import ua.vtc.rozklad.core.catalog.internal.CatalogImportDtos.AliasType;
import ua.vtc.rozklad.core.catalog.internal.CatalogImportDtos.CatalogImportResult;
import ua.vtc.rozklad.core.catalog.internal.CatalogImportDtos.DepartmentItem;
import ua.vtc.rozklad.core.catalog.internal.CatalogImportDtos.DisciplineItem;
import ua.vtc.rozklad.core.catalog.internal.CatalogImportDtos.GroupItem;
import ua.vtc.rozklad.core.catalog.internal.CatalogImportDtos.NamedItem;
import ua.vtc.rozklad.core.catalog.internal.CatalogImportDtos.RoomItem;
import ua.vtc.rozklad.core.catalog.internal.CatalogImportDtos.SpecialtyItem;
import ua.vtc.rozklad.core.catalog.internal.CatalogImportDtos.TeacherItem;
import ua.vtc.rozklad.core.shared.error.FieldErrors;

/**
 * One catalog import: lookup maps keyed by natural keys, counters, ids and errors. Existing
 * records get only the fields the item sends ({@code null} keeps the stored value); flags that
 * the admin reviews ({@code needsReview}) are set only on creation.
 */
final class CatalogImportRun {

    private final Snapshot existing;
    private final CatalogImportService.Writer writer;
    private final FieldErrors errors = new FieldErrors();
    private final Map<String, Map<String, UUID>> ids = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private final Set<String> importedGroupCodes = new LinkedHashSet<>();
    private int created;
    private int updated;
    private int unchanged;

    CatalogImportRun(Snapshot existing, CatalogImportService.Writer writer) {
        this.existing = existing;
        this.writer = writer;
    }

    void departments(List<DepartmentItem> items) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            DepartmentItem item = items.get(i);
            String name = CatalogText.clean(item.name());
            String key = name.toLowerCase(CatalogText.UKRAINIAN);
            if (!seen.add(key)) {
                errors.add("departments[%d].name".formatted(i), "Відділення «%s» повторюється в запиті.".formatted(name));
                continue;
            }
            Department department = existing.departments().get(key);
            if (department == null) {
                department = writer.save(new Department(name, DepartmentService.blankToNull(item.shortName())));
                existing.departments().put(key, department);
                created++;
            } else {
                Department target = department;
                Changes changes = new Changes();
                changes.set(target.getShortName(), DepartmentService.blankToNull(item.shortName()), target::setShortName);
                count(changes);
            }
            id("departments", item.name(), department.getId());
        }
    }

    void specialties(List<SpecialtyItem> items) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            SpecialtyItem item = items.get(i);
            String field = "specialties[%d]".formatted(i);
            Optional<String> letters = GroupCodes.specialtyLetters(item.letters());
            if (letters.isEmpty()) {
                errors.add(field + ".letters", SpecialtyService.LETTERS_FORMAT);
                continue;
            }
            if (!seen.add(letters.get())) {
                errors.add(field + ".letters", "Спеціальність «%s» повторюється в запиті.".formatted(letters.get()));
                continue;
            }
            UUID departmentId = null;
            if (item.departmentName() != null && !item.departmentName().isBlank()) {
                Department department = existing.departments()
                        .get(CatalogText.clean(item.departmentName()).toLowerCase(CatalogText.UKRAINIAN));
                if (department == null) {
                    errors.add(field + ".departmentName", "Відділення «%s» немає ні в довіднику, ні в запиті."
                            .formatted(item.departmentName()));
                    continue;
                }
                departmentId = department.getId();
            }
            String name = DepartmentService.blankToNull(item.name());
            Specialty specialty = existing.specialties().get(letters.get());
            if (specialty == null) {
                specialty = new Specialty(letters.get(), name != null ? name : letters.get());
                specialty.setCode(DepartmentService.blankToNull(item.code()));
                specialty.setDepartmentId(departmentId);
                writer.save(specialty);
                existing.specialties().put(letters.get(), specialty);
                created++;
                if (name == null) {
                    warnings.add("Спеціальність «%s» створено без повної назви: уточніть її в довіднику."
                            .formatted(letters.get()));
                }
            } else {
                Specialty target = specialty;
                Changes changes = new Changes();
                changes.set(target.getName(), name, target::setName);
                changes.set(target.getCode(), DepartmentService.blankToNull(item.code()), target::setCode);
                changes.set(target.getDepartmentId(), departmentId, target::setDepartmentId);
                count(changes);
            }
            id("specialties", item.letters(), specialty.getId());
        }
    }

    void cycleCommissions(List<NamedItem> items) {
        for (int i = 0; i < items.size(); i++) {
            CycleCommission commission = commission(items.get(i).name());
            id("cycleCommissions", items.get(i).name(), commission.getId());
        }
    }

    void buildings(List<NamedItem> items) {
        for (int i = 0; i < items.size(); i++) {
            Building building = building(items.get(i).name());
            id("buildings", items.get(i).name(), building.getId());
        }
    }

    void teachers(List<TeacherItem> items) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            TeacherItem item = items.get(i);
            String field = "teachers[%d]".formatted(i);
            String shortName = CatalogText.clean(item.shortName());
            String key = CatalogText.key(shortName);
            if (key.isEmpty()) {
                errors.add(field + ".shortName", "Коротке ім'я має містити літери.");
                continue;
            }
            if (!seen.add(key)) {
                errors.add(field + ".shortName", "Викладач «%s» повторюється в запиті.".formatted(shortName));
                continue;
            }
            UUID commissionId = item.cycleCommissionName() == null || item.cycleCommissionName().isBlank()
                    ? null : commission(item.cycleCommissionName()).getId();
            Teacher teacher = findTeacher(key).orElse(null);
            if (teacher == null) {
                String lastName = Optional.ofNullable(DepartmentService.blankToNull(item.lastName()))
                        .orElseGet(() -> TeacherNames.lastNameOf(shortName));
                teacher = new Teacher(lastName, shortName,
                        Optional.ofNullable(DepartmentService.blankToNull(item.displayName())).orElse(lastName));
                teacher.setFirstName(DepartmentService.blankToNull(item.firstName()));
                teacher.setMiddleName(DepartmentService.blankToNull(item.middleName()));
                teacher.setCycleCommissionId(commissionId);
                teacher.setEmployment(item.employment() == null ? Employment.STAFF : item.employment());
                teacher.setEmail(DepartmentService.blankToNull(item.email()));
                teacher.setNeedsReview(Boolean.TRUE.equals(item.needsReview()));
                writer.save(teacher);
                existing.teachers().put(key, teacher);
                created++;
            } else {
                Teacher target = teacher;
                Changes changes = new Changes();
                changes.set(target.getLastName(), DepartmentService.blankToNull(item.lastName()), target::setLastName);
                changes.set(target.getFirstName(), DepartmentService.blankToNull(item.firstName()), target::setFirstName);
                changes.set(target.getMiddleName(), DepartmentService.blankToNull(item.middleName()),
                        target::setMiddleName);
                changes.set(target.getDisplayName(), DepartmentService.blankToNull(item.displayName()),
                        target::setDisplayName);
                changes.set(target.getCycleCommissionId(), commissionId, target::setCycleCommissionId);
                changes.set(target.getEmployment(), item.employment(), target::setEmployment);
                changes.set(target.getEmail(), DepartmentService.blankToNull(item.email()), target::setEmail);
                count(changes);
            }
            id("teachers", item.shortName(), teacher.getId());
        }
    }

    void disciplines(List<DisciplineItem> items) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            DisciplineItem item = items.get(i);
            String field = "disciplines[%d]".formatted(i);
            String shortName = CatalogText.clean(item.shortName());
            String key = CatalogText.key(shortName);
            if (key.isEmpty()) {
                errors.add(field + ".shortName", "Скорочена назва має містити літери або цифри.");
                continue;
            }
            if (!seen.add(key)) {
                errors.add(field + ".shortName", "Дисципліна «%s» повторюється в запиті.".formatted(shortName));
                continue;
            }
            String name = DepartmentService.blankToNull(item.name());
            String color = item.color() == null ? null : item.color().toUpperCase(java.util.Locale.ROOT);
            Discipline discipline = findDiscipline(key).orElse(null);
            if (discipline == null) {
                discipline = new Discipline(name != null ? name : shortName, shortName);
                discipline.setColor(color);
                // DOMAIN §10: повна назва невідома → лишаємо абревіатуру і просимо адміністратора перевірити
                discipline.setNeedsReview(name == null || Boolean.TRUE.equals(item.needsReview()));
                writer.save(discipline);
                existing.disciplines().put(key, discipline);
                created++;
            } else {
                Discipline target = discipline;
                Changes changes = new Changes();
                changes.set(target.getName(), name, target::setName);
                changes.set(target.getColor(), color, target::setColor);
                count(changes);
            }
            id("disciplines", item.shortName(), discipline.getId());
        }
    }

    void rooms(List<RoomItem> items) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            RoomItem item = items.get(i);
            String field = "rooms[%d]".formatted(i);
            String name = CatalogText.clean(item.name());
            String key = CatalogText.key(name);
            if (key.isEmpty()) {
                errors.add(field + ".name", "Назва аудиторії має містити літери або цифри.");
                continue;
            }
            if (!seen.add(key)) {
                errors.add(field + ".name", "Аудиторія «%s» повторюється в запиті.".formatted(name));
                continue;
            }
            UUID buildingId = item.buildingName() == null || item.buildingName().isBlank()
                    ? null : building(item.buildingName()).getId();
            Room room = existing.rooms().get(key);
            if (room == null) {
                room = new Room(name, item.type() == null ? RoomType.CLASSROOM : item.type());
                room.setCapacity(item.capacity());
                room.setMaxParallel(item.maxParallel() == null ? 1 : item.maxParallel());
                room.setBuildingId(buildingId);
                writer.save(room);
                existing.rooms().put(key, room);
                created++;
            } else {
                Room target = room;
                Changes changes = new Changes();
                changes.set(target.getType(), item.type(), target::setType);
                changes.set(target.getCapacity(), item.capacity(), target::setCapacity);
                changes.set(target.getMaxParallel(), item.maxParallel(), target::setMaxParallel);
                changes.set(target.getBuildingId(), buildingId, target::setBuildingId);
                count(changes);
            }
            id("rooms", item.name(), room.getId());
        }
    }

    void groups(List<GroupItem> items) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            GroupItem item = items.get(i);
            String field = "groups[%d]".formatted(i);
            Optional<GroupCodes.GroupCode> parsed = GroupCodes.canonical(item.code()).flatMap(GroupCodes::parse);
            if (parsed.isEmpty() || parsed.get().course() < 1 || parsed.get().course() > 6) {
                errors.add(field + ".code", GroupService.CODE_FORMAT);
                continue;
            }
            GroupCodes.GroupCode code = parsed.get();
            if (!seen.add(code.value())) {
                errors.add(field + ".code", "Група %s повторюється в запиті.".formatted(code.value()));
                continue;
            }
            if (!groupItemAgreesWithCode(item, code, field)) {
                continue;
            }
            UUID homeRoomId = resolveRoom(item.homeRoomName(), field + ".homeRoomName");
            UUID curatorId = resolveTeacher(item.curatorShortName(), field + ".curatorShortName");
            Specialty specialty = specialtyFor(code);
            StudentGroup group = existing.groups().get(code.value());
            if (group == null) {
                group = new StudentGroup(code.value(), code.course(), specialty.getId());
                group.setNumber(code.number());
                group.setSize(item.size());
                group.setShift(item.shift() != null ? item.shift() : GroupService.defaultShift(code.course()));
                group.setHomeRoomId(homeRoomId);
                group.setCuratorTeacherId(curatorId);
                writer.save(group);
                existing.groups().put(code.value(), group);
                created++;
            } else {
                StudentGroup target = group;
                Changes changes = new Changes();
                changes.set(target.getSize(), item.size(), target::setSize);
                changes.set(target.getShift(), item.shift(), target::setShift);
                changes.set(target.getHomeRoomId(), homeRoomId, target::setHomeRoomId);
                changes.set(target.getCuratorTeacherId(), curatorId, target::setCuratorTeacherId);
                count(changes);
            }
            ensureSubgroups(group, item.subgroups());
            importedGroupCodes.add(code.value());
            id("groups", item.code(), group.getId());
        }
    }

    void aliases(List<AliasItem> items) {
        for (int i = 0; i < items.size(); i++) {
            AliasItem item = items.get(i);
            String field = "aliases[%d]".formatted(i);
            String alias = CatalogText.clean(item.alias());
            String key = CatalogText.key(alias);
            String targetKey = CatalogText.key(item.target());
            if (key.isEmpty()) {
                errors.add(field + ".alias", "Псевдонім має містити літери або цифри.");
                continue;
            }
            if (item.type() == AliasType.TEACHER) {
                teacherAlias(field, alias, key, targetKey, item.target());
            } else {
                disciplineAlias(field, alias, key, targetKey, item.target());
            }
        }
    }

    CatalogImportResult finish() {
        errors.throwIfAny();
        return new CatalogImportResult(created, updated, unchanged, ids, List.copyOf(warnings));
    }

    Set<String> importedGroupCodes() {
        return importedGroupCodes;
    }

    private void teacherAlias(String field, String alias, String key, String targetKey, String targetName) {
        Optional<Teacher> target = findTeacher(targetKey);
        if (target.isEmpty()) {
            errors.add(field + ".target", "Викладача «%s» немає ні в довіднику, ні в запиті.".formatted(targetName));
            return;
        }
        UUID teacherId = target.get().getId();
        Teacher owner = existing.teachers().get(key);
        if (owner != null) {
            if (owner.getId().equals(teacherId)) {
                unchanged++;
            } else {
                errors.add(field + ".alias", "«%s» — основне ім'я іншого викладача.".formatted(alias));
            }
            return;
        }
        TeacherAlias present = existing.teacherAliases().get(key);
        if (present != null) {
            if (present.getTeacherId().equals(teacherId)) {
                unchanged++;
            } else {
                errors.add(field + ".alias", "Псевдонім «%s» уже належить іншому викладачу.".formatted(alias));
            }
            return;
        }
        TeacherAlias created = new TeacherAlias(teacherId, alias, key);
        writer.save(created);
        existing.teacherAliases().put(key, created);
        this.created++;
    }

    private void disciplineAlias(String field, String alias, String key, String targetKey, String targetName) {
        Optional<Discipline> target = findDiscipline(targetKey);
        if (target.isEmpty()) {
            errors.add(field + ".target", "Дисципліни «%s» немає ні в довіднику, ні в запиті.".formatted(targetName));
            return;
        }
        UUID disciplineId = target.get().getId();
        Discipline owner = existing.disciplines().get(key);
        if (owner != null) {
            if (owner.getId().equals(disciplineId)) {
                unchanged++;
            } else {
                errors.add(field + ".alias", "«%s» — скорочена назва іншої дисципліни.".formatted(alias));
            }
            return;
        }
        DisciplineAlias present = existing.disciplineAliases().get(key);
        if (present != null) {
            if (present.getDisciplineId().equals(disciplineId)) {
                unchanged++;
            } else {
                errors.add(field + ".alias", "Псевдонім «%s» уже належить іншій дисципліні.".formatted(alias));
            }
            return;
        }
        DisciplineAlias created = new DisciplineAlias(disciplineId, alias, key);
        writer.save(created);
        existing.disciplineAliases().put(key, created);
        this.created++;
    }

    private boolean groupItemAgreesWithCode(GroupItem item, GroupCodes.GroupCode code, String field) {
        boolean ok = true;
        if (item.course() != null && item.course() != code.course()) {
            errors.add(field + ".course", "Курс не збігається з кодом групи (%d).".formatted(code.course()));
            ok = false;
        }
        if (item.specialtyLetters() != null && !item.specialtyLetters().isBlank()
                && !GroupCodes.specialtyLetters(item.specialtyLetters()).map(code.letters()::equals).orElse(false)) {
            errors.add(field + ".specialtyLetters", "Літери спеціальності не збігаються з кодом групи (%s)."
                    .formatted(code.letters()));
            ok = false;
        }
        return ok;
    }

    /** Specialty of the code letters; created with the letters as name when nobody sent it. */
    private Specialty specialtyFor(GroupCodes.GroupCode code) {
        Specialty specialty = existing.specialties().get(code.letters());
        if (specialty == null) {
            specialty = writer.save(new Specialty(code.letters(), code.letters()));
            existing.specialties().put(code.letters(), specialty);
            created++;
            warnings.add("Спеціальність «%s» створено автоматично для групи %s: додайте її повну назву й відділення."
                    .formatted(code.letters(), code.value()));
        }
        return specialty;
    }

    private void ensureSubgroups(StudentGroup group, List<Integer> numbers) {
        if (numbers == null || numbers.isEmpty()) {
            return;
        }
        Set<Integer> present = existing.subgroupNumbers().computeIfAbsent(group.getId(), id -> new HashSet<>());
        for (Integer number : new java.util.TreeSet<>(numbers)) {
            if (present.add(number)) {
                writer.save(new Subgroup(group.getId(), number));
                created++;
            }
        }
    }

    private UUID resolveRoom(String name, String field) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Room room = existing.rooms().get(CatalogText.key(name));
        if (room == null) {
            errors.add(field, "Аудиторії «%s» немає ні в довіднику, ні в запиті.".formatted(name));
            return null;
        }
        return room.getId();
    }

    private UUID resolveTeacher(String shortName, String field) {
        if (shortName == null || shortName.isBlank()) {
            return null;
        }
        Optional<Teacher> teacher = findTeacher(CatalogText.key(shortName));
        if (teacher.isEmpty()) {
            errors.add(field, "Викладача «%s» немає ні в довіднику, ні в запиті.".formatted(shortName));
            return null;
        }
        return teacher.get().getId();
    }

    /** Main short name first, then an alias («Граболінська» → «Гарболінська О.»). */
    private Optional<Teacher> findTeacher(String key) {
        Teacher byName = existing.teachers().get(key);
        if (byName != null) {
            return Optional.of(byName);
        }
        TeacherAlias alias = existing.teacherAliases().get(key);
        return alias == null ? Optional.empty() : existing.teachers().values().stream()
                .filter(teacher -> teacher.getId().equals(alias.getTeacherId()))
                .findFirst()
                .or(() -> writer.teacher(alias.getTeacherId()));
    }

    private Optional<Discipline> findDiscipline(String key) {
        Discipline byName = existing.disciplines().get(key);
        if (byName != null) {
            return Optional.of(byName);
        }
        DisciplineAlias alias = existing.disciplineAliases().get(key);
        return alias == null ? Optional.empty() : existing.disciplines().values().stream()
                .filter(discipline -> discipline.getId().equals(alias.getDisciplineId()))
                .findFirst()
                .or(() -> writer.discipline(alias.getDisciplineId()));
    }

    private CycleCommission commission(String rawName) {
        String name = CatalogText.clean(rawName);
        String key = name.toLowerCase(CatalogText.UKRAINIAN);
        CycleCommission commission = existing.commissions().get(key);
        if (commission == null) {
            commission = writer.save(new CycleCommission(name));
            existing.commissions().put(key, commission);
            created++;
        }
        return commission;
    }

    private Building building(String rawName) {
        String name = CatalogText.clean(rawName);
        String key = name.toLowerCase(CatalogText.UKRAINIAN);
        Building building = existing.buildings().get(key);
        if (building == null) {
            building = writer.save(new Building(name));
            existing.buildings().put(key, building);
            created++;
        }
        return building;
    }

    private void id(String section, String naturalKey, UUID id) {
        ids.computeIfAbsent(section, ignored -> new LinkedHashMap<>()).put(naturalKey, id);
    }

    private void count(Changes changes) {
        if (changes.any) {
            updated++;
        } else {
            unchanged++;
        }
    }

    /** Applies a value only when it is sent and differs, remembering whether anything changed. */
    private static final class Changes {

        private boolean any;

        <T> void set(T current, T next, Consumer<T> setter) {
            if (next != null && !Objects.equals(current, next)) {
                setter.accept(next);
                any = true;
            }
        }
    }

    /** Existing records keyed by natural key; new records are added while the run proceeds. */
    record Snapshot(
            Map<String, Department> departments,
            Map<String, Specialty> specialties,
            Map<String, CycleCommission> commissions,
            Map<String, Building> buildings,
            Map<String, Teacher> teachers,
            Map<String, TeacherAlias> teacherAliases,
            Map<String, Discipline> disciplines,
            Map<String, DisciplineAlias> disciplineAliases,
            Map<String, Room> rooms,
            Map<String, StudentGroup> groups,
            Map<UUID, Set<Integer>> subgroupNumbers) {
    }
}
