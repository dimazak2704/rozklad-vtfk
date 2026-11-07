package ua.vtc.rozklad.core.substitution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ua.vtc.rozklad.contracts.AbsenceReasonCategory;
import ua.vtc.rozklad.contracts.AbsenceSource;
import ua.vtc.rozklad.core.shared.domain.BaseEntity;

/**
 * Відсутність викладача on {@code [dateFrom, dateTo]}; {@link #getPairs()} empty means the whole
 * day. {@code reasonCategory} and {@code comment} are private: they never leave the admin UI and
 * must not be logged or put into events.
 */
@Entity
@Table(name = "absence")
public class Absence extends BaseEntity {

    @Column(name = "teacher_id", nullable = false, updatable = false)
    private UUID teacherId;

    @Column(name = "date_from", nullable = false)
    private LocalDate dateFrom;

    @Column(name = "date_to", nullable = false)
    private LocalDate dateTo;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "pairs", columnDefinition = "smallint[]")
    private Short[] pairs;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_category", nullable = false, length = 20)
    private AbsenceReasonCategory reasonCategory;

    @Column(name = "comment", columnDefinition = "text")
    private String comment;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16, updatable = false)
    private AbsenceSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AbsenceStatus status = AbsenceStatus.ACTIVE;

    @Column(name = "created_by", length = 100, updatable = false)
    private String createdBy;

    protected Absence() {
    }

    public Absence(UUID teacherId, LocalDate dateFrom, LocalDate dateTo, AbsenceReasonCategory reasonCategory,
                   AbsenceSource source, String createdBy) {
        this.teacherId = Objects.requireNonNull(teacherId, "teacherId");
        setDates(dateFrom, dateTo);
        setReasonCategory(reasonCategory);
        this.source = Objects.requireNonNull(source, "source");
        this.createdBy = createdBy;
    }

    public UUID getTeacherId() {
        return teacherId;
    }

    public LocalDate getDateFrom() {
        return dateFrom;
    }

    public LocalDate getDateTo() {
        return dateTo;
    }

    public void setDates(LocalDate dateFrom, LocalDate dateTo) {
        Objects.requireNonNull(dateFrom, "dateFrom");
        Objects.requireNonNull(dateTo, "dateTo");
        if (dateTo.isBefore(dateFrom)) {
            throw new IllegalArgumentException("dateTo must not be before dateFrom");
        }
        this.dateFrom = dateFrom;
        this.dateTo = dateTo;
    }

    /** Absent pairs in ascending order; empty = the whole day. */
    public List<Integer> getPairs() {
        return pairs == null ? List.of() : Arrays.stream(pairs).map(Short::intValue).toList();
    }

    public boolean isWholeDay() {
        return pairs == null;
    }

    /** {@code null} or empty = the whole day. */
    public void setPairs(Collection<Integer> pairNumbers) {
        if (pairNumbers == null || pairNumbers.isEmpty()) {
            this.pairs = null;
            return;
        }
        if (pairNumbers.stream().anyMatch(pair -> pair == null || pair < 1 || pair > 8)) {
            throw new IllegalArgumentException("pairs must be 1..8");
        }
        this.pairs = pairNumbers.stream().distinct().sorted().map(Integer::shortValue).toArray(Short[]::new);
    }

    public boolean covers(LocalDate date, int pair) {
        boolean inDates = !date.isBefore(dateFrom) && !date.isAfter(dateTo);
        return inDates && (isWholeDay() || getPairs().contains(pair));
    }

    public AbsenceReasonCategory getReasonCategory() {
        return reasonCategory;
    }

    public void setReasonCategory(AbsenceReasonCategory reasonCategory) {
        this.reasonCategory = Objects.requireNonNull(reasonCategory, "reasonCategory");
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public AbsenceSource getSource() {
        return source;
    }

    public AbsenceStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == AbsenceStatus.ACTIVE;
    }

    public void cancel() {
        this.status = AbsenceStatus.CANCELLED;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    /** Never expose reason/comment here: the record may end up in logs. */
    @Override
    public String toString() {
        return "Absence[id=" + getId() + ", teacherId=" + teacherId + ", " + dateFrom + ".." + dateTo + "]";
    }
}
