package ua.vtc.rozklad.core.calendar;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ua.vtc.rozklad.core.shared.domain.BaseEntity;

/**
 * Holiday, practice, study mode … in a semester. Scope ALL / COURSES ({@code courses}) /
 * GROUPS (rows of {@code calendar_period_group}). {@code orderNote} is copied into orders
 * («Групи 4КІ з 01.09.26р. по 31.10.26 навчаються – дистанційно.»).
 */
@Entity
@Table(name = "calendar_period")
public class CalendarPeriod extends BaseEntity {

    @Column(name = "semester_id", nullable = false)
    private UUID semesterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private CalendarPeriodKind kind;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "study_mode", length = 16)
    private StudyMode studyMode;

    @Column(name = "order_note", columnDefinition = "text")
    private String orderNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 16)
    private PeriodScope scope = PeriodScope.ALL;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "courses", columnDefinition = "smallint[]")
    private Short[] courses;

    @OneToMany(mappedBy = "period", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<CalendarPeriodGroup> groups = new HashSet<>();

    protected CalendarPeriod() {
    }

    public CalendarPeriod(UUID semesterId, CalendarPeriodKind kind, String title, LocalDate startsOn,
                          LocalDate endsOn) {
        this.semesterId = Objects.requireNonNull(semesterId, "semesterId");
        setKind(kind);
        setTitle(title);
        setPeriod(startsOn, endsOn);
    }

    public UUID getSemesterId() {
        return semesterId;
    }

    public CalendarPeriodKind getKind() {
        return kind;
    }

    public void setKind(CalendarPeriodKind kind) {
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = Objects.requireNonNull(title, "title");
    }

    public LocalDate getStartsOn() {
        return startsOn;
    }

    public LocalDate getEndsOn() {
        return endsOn;
    }

    public void setPeriod(LocalDate startsOn, LocalDate endsOn) {
        Objects.requireNonNull(startsOn, "startsOn");
        Objects.requireNonNull(endsOn, "endsOn");
        if (endsOn.isBefore(startsOn)) {
            throw new IllegalArgumentException("endsOn must not be before startsOn");
        }
        this.startsOn = startsOn;
        this.endsOn = endsOn;
    }

    public boolean covers(LocalDate date) {
        return !date.isBefore(startsOn) && !date.isAfter(endsOn);
    }

    public StudyMode getStudyMode() {
        return studyMode;
    }

    public void setStudyMode(StudyMode studyMode) {
        this.studyMode = studyMode;
    }

    public String getOrderNote() {
        return orderNote;
    }

    public void setOrderNote(String orderNote) {
        this.orderNote = orderNote;
    }

    public PeriodScope getScope() {
        return scope;
    }

    /** Courses for scope COURSES (empty otherwise). */
    public List<Integer> getCourses() {
        return courses == null ? List.of() : Arrays.stream(courses).map(Short::intValue).toList();
    }

    public Set<UUID> getGroupIds() {
        return groups.stream().map(CalendarPeriodGroup::getGroupId).collect(Collectors.toUnmodifiableSet());
    }

    public void applyToAll() {
        this.scope = PeriodScope.ALL;
        this.courses = null;
        this.groups.clear();
    }

    public void applyToCourses(Collection<Integer> courseNumbers) {
        if (courseNumbers == null || courseNumbers.isEmpty()) {
            throw new IllegalArgumentException("courses must not be empty");
        }
        this.scope = PeriodScope.COURSES;
        this.courses = courseNumbers.stream().distinct().sorted().map(Integer::shortValue).toArray(Short[]::new);
        this.groups.clear();
    }

    public void applyToGroups(Collection<UUID> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            throw new IllegalArgumentException("groups must not be empty");
        }
        this.scope = PeriodScope.GROUPS;
        this.courses = null;
        Set<UUID> wanted = Set.copyOf(groupIds);
        groups.removeIf(link -> !wanted.contains(link.getGroupId()));
        Set<UUID> present = getGroupIds();
        wanted.stream().filter(id -> !present.contains(id))
                .forEach(id -> groups.add(new CalendarPeriodGroup(this, id)));
    }
}
