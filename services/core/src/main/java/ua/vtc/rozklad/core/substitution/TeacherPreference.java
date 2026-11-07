package ua.vtc.rozklad.core.substitution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.core.shared.domain.IdentifiableEntity;

/** Unwanted or unavailable slot (weekday × pair) of a teacher in a semester. */
@Entity
@Table(name = "teacher_preference")
public class TeacherPreference extends IdentifiableEntity {

    @Column(name = "teacher_id", nullable = false, updatable = false)
    private UUID teacherId;

    @Column(name = "semester_id", nullable = false, updatable = false)
    private UUID semesterId;

    @Column(name = "weekday", nullable = false)
    private int weekday;

    @Column(name = "pair_number", nullable = false)
    private int pairNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private PreferenceKind kind;

    @Column(name = "note", length = 500)
    private String note;

    protected TeacherPreference() {
    }

    public TeacherPreference(UUID teacherId, UUID semesterId, int weekday, int pairNumber, PreferenceKind kind,
                             String note) {
        if (weekday < 1 || weekday > 5 || pairNumber < 1 || pairNumber > 8) {
            throw new IllegalArgumentException("weekday must be 1..5 and pair 1..8");
        }
        this.teacherId = Objects.requireNonNull(teacherId, "teacherId");
        this.semesterId = Objects.requireNonNull(semesterId, "semesterId");
        this.weekday = weekday;
        this.pairNumber = pairNumber;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.note = note;
    }

    public UUID getTeacherId() {
        return teacherId;
    }

    public UUID getSemesterId() {
        return semesterId;
    }

    public int getWeekday() {
        return weekday;
    }

    public int getPairNumber() {
        return pairNumber;
    }

    public PreferenceKind getKind() {
        return kind;
    }

    public String getNote() {
        return note;
    }
}
