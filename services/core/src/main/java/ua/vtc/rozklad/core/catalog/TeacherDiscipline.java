package ua.vtc.rozklad.core.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.core.shared.domain.IdentifiableEntity;

/** Competence: the teacher can teach the discipline (used by substitution candidate ranking). */
@Entity
@Table(name = "teacher_discipline")
public class TeacherDiscipline extends IdentifiableEntity {

    @Column(name = "teacher_id", nullable = false)
    private UUID teacherId;

    @Column(name = "discipline_id", nullable = false)
    private UUID disciplineId;

    protected TeacherDiscipline() {
    }

    public TeacherDiscipline(UUID teacherId, UUID disciplineId) {
        this.teacherId = Objects.requireNonNull(teacherId, "teacherId");
        this.disciplineId = Objects.requireNonNull(disciplineId, "disciplineId");
    }

    public UUID getTeacherId() {
        return teacherId;
    }

    public UUID getDisciplineId() {
        return disciplineId;
    }
}
