package ua.vtc.rozklad.core.catalog.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.catalog.CatalogText;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.DisciplineAlias;
import ua.vtc.rozklad.core.catalog.DisciplineAliasRepository;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherAlias;
import ua.vtc.rozklad.core.catalog.TeacherAliasRepository;
import ua.vtc.rozklad.core.catalog.internal.TeacherDtos.AliasDto;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.FieldValidationException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;

/**
 * Псевдоніми: other spellings of a teacher («Граболінська» for «Гарболінська О.») or a discipline
 * («НДКС» for «НДЕКСМ») found in imports and orders. The normalized key (DOMAIN §10) is unique
 * across all aliases and must not equal another record's main short name, otherwise matching
 * would be ambiguous.
 */
@Service
@Transactional(readOnly = true)
public class AliasService {

    private final TeacherAliasRepository teacherAliases;
    private final DisciplineAliasRepository disciplineAliases;
    private final TeacherService teachers;
    private final DisciplineService disciplines;

    public AliasService(TeacherAliasRepository teacherAliases, DisciplineAliasRepository disciplineAliases,
                        TeacherService teachers, DisciplineService disciplines) {
        this.teacherAliases = teacherAliases;
        this.disciplineAliases = disciplineAliases;
        this.teachers = teachers;
        this.disciplines = disciplines;
    }

    public List<AliasDto> teacherAliases(UUID teacherId) {
        teachers.find(teacherId);
        return teacherAliases.findAllByTeacherIdOrderByAlias(teacherId).stream()
                .map(alias -> new AliasDto(alias.getId(), alias.getAlias()))
                .toList();
    }

    @Transactional
    public AliasDto addTeacherAlias(UUID teacherId, String rawAlias) {
        Teacher teacher = teachers.find(teacherId);
        String alias = CatalogText.clean(rawAlias);
        String key = keyOf(alias);
        if (key.equals(CatalogText.key(teacher.getShortName()))) {
            throw FieldValidationException.of("alias", "Псевдонім збігається з основним іменем викладача.");
        }
        teachers.findByShortNameKey(key, teacherId).ifPresent(other -> {
            throw new ConflictException("«%s» — основне ім'я іншого викладача.".formatted(other.getShortName()));
        });
        teacherAliases.findByNormalized(key).ifPresent(existing -> {
            throw new ConflictException(existing.getTeacherId().equals(teacherId)
                    ? "Такий псевдонім уже є."
                    : "Псевдонім «%s» уже належить іншому викладачу.".formatted(existing.getAlias()));
        });
        TeacherAlias saved = teacherAliases.save(new TeacherAlias(teacherId, alias, key));
        return new AliasDto(saved.getId(), saved.getAlias());
    }

    @Transactional
    public void removeTeacherAlias(UUID teacherId, UUID aliasId) {
        TeacherAlias alias = teacherAliases.findById(aliasId)
                .filter(found -> found.getTeacherId().equals(teacherId))
                .orElseThrow(() -> NotFoundException.of("Псевдонім", aliasId));
        teacherAliases.delete(alias);
    }

    public List<AliasDto> disciplineAliases(UUID disciplineId) {
        disciplines.find(disciplineId);
        return disciplineAliases.findAllByDisciplineIdOrderByAlias(disciplineId).stream()
                .map(alias -> new AliasDto(alias.getId(), alias.getAlias()))
                .toList();
    }

    @Transactional
    public AliasDto addDisciplineAlias(UUID disciplineId, String rawAlias) {
        Discipline discipline = disciplines.find(disciplineId);
        String alias = CatalogText.clean(rawAlias);
        String key = keyOf(alias);
        if (key.equals(CatalogText.key(discipline.getShortName()))) {
            throw FieldValidationException.of("alias", "Псевдонім збігається зі скороченою назвою дисципліни.");
        }
        disciplines.findByShortNameKey(key, disciplineId).ifPresent(other -> {
            throw new ConflictException("«%s» — скорочена назва іншої дисципліни.".formatted(other.getShortName()));
        });
        disciplineAliases.findByNormalized(key).ifPresent(existing -> {
            throw new ConflictException(existing.getDisciplineId().equals(disciplineId)
                    ? "Такий псевдонім уже є."
                    : "Псевдонім «%s» уже належить іншій дисципліні.".formatted(existing.getAlias()));
        });
        DisciplineAlias saved = disciplineAliases.save(new DisciplineAlias(disciplineId, alias, key));
        return new AliasDto(saved.getId(), saved.getAlias());
    }

    @Transactional
    public void removeDisciplineAlias(UUID disciplineId, UUID aliasId) {
        DisciplineAlias alias = disciplineAliases.findById(aliasId)
                .filter(found -> found.getDisciplineId().equals(disciplineId))
                .orElseThrow(() -> NotFoundException.of("Псевдонім", aliasId));
        disciplineAliases.delete(alias);
    }

    private static String keyOf(String alias) {
        String key = CatalogText.key(alias);
        if (key.isEmpty()) {
            throw FieldValidationException.of("alias", "Псевдонім має містити літери або цифри.");
        }
        if (key.length() > 100) {
            throw FieldValidationException.of("alias", "Псевдонім задовгий.");
        }
        return key;
    }
}
