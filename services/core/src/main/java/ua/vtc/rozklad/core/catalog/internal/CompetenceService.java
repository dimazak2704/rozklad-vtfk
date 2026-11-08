package ua.vtc.rozklad.core.catalog.internal;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.DisciplineRef;
import ua.vtc.rozklad.core.catalog.DisciplineRepository;
import ua.vtc.rozklad.core.catalog.TeacherDiscipline;
import ua.vtc.rozklad.core.catalog.TeacherDisciplineRepository;
import ua.vtc.rozklad.core.shared.error.FieldErrors;
import ua.vtc.rozklad.core.shared.error.NotFoundException;

/**
 * Competences «викладач веде дисципліну» ({@code /api/admin/teachers/{id}/disciplines}); the
 * substitution ranking gives +50 to a candidate who teaches the discipline (DOMAIN §7).
 */
@Service
@Transactional(readOnly = true)
public class CompetenceService {

    private final TeacherDisciplineRepository competences;
    private final DisciplineRepository disciplines;
    private final TeacherService teachers;

    public CompetenceService(TeacherDisciplineRepository competences, DisciplineRepository disciplines,
                             TeacherService teachers) {
        this.competences = competences;
        this.disciplines = disciplines;
        this.teachers = teachers;
    }

    public List<DisciplineRef> list(UUID teacherId) {
        teachers.find(teacherId);
        Set<UUID> ids = competences.findAllByTeacherId(teacherId).stream()
                .map(TeacherDiscipline::getDisciplineId).collect(Collectors.toSet());
        return disciplines.findAllById(ids).stream()
                .map(DisciplineRef::of)
                .sorted(Comparator.comparing(DisciplineRef::shortName))
                .toList();
    }

    /** Replaces the whole set; unknown discipline ids are field errors {@code disciplineIds[i]}. */
    @Transactional
    public List<DisciplineRef> replace(UUID teacherId, List<UUID> disciplineIds) {
        teachers.find(teacherId);
        Set<UUID> wanted = new LinkedHashSet<>(disciplineIds);
        Set<UUID> existing = disciplines.findAllById(wanted).stream().map(Discipline::getId).collect(Collectors.toSet());
        FieldErrors errors = new FieldErrors();
        for (int i = 0; i < disciplineIds.size(); i++) {
            if (!existing.contains(disciplineIds.get(i))) {
                errors.add("disciplineIds[" + i + "]", "Дисципліну не знайдено.");
            }
        }
        errors.throwIfAny();
        List<TeacherDiscipline> current = competences.findAllByTeacherId(teacherId);
        current.stream().filter(link -> !wanted.contains(link.getDisciplineId())).forEach(competences::delete);
        Set<UUID> present = current.stream().map(TeacherDiscipline::getDisciplineId).collect(Collectors.toSet());
        wanted.stream().filter(id -> !present.contains(id))
                .forEach(id -> competences.save(new TeacherDiscipline(teacherId, id)));
        competences.flush();
        return list(teacherId);
    }

    @Transactional
    public List<DisciplineRef> add(UUID teacherId, UUID disciplineId) {
        teachers.find(teacherId);
        if (!disciplines.existsById(disciplineId)) {
            new FieldErrors().add("disciplineId", "Дисципліну не знайдено.").throwIfAny();
        }
        if (!competences.existsByTeacherIdAndDisciplineId(teacherId, disciplineId)) {
            competences.save(new TeacherDiscipline(teacherId, disciplineId));
            competences.flush();
        }
        return list(teacherId);
    }

    @Transactional
    public void remove(UUID teacherId, UUID disciplineId) {
        teachers.find(teacherId);
        TeacherDiscipline link = competences.findByTeacherIdAndDisciplineId(teacherId, disciplineId)
                .orElseThrow(() -> new NotFoundException("Викладач не має такої дисципліни у компетенціях."));
        competences.delete(link);
    }
}
