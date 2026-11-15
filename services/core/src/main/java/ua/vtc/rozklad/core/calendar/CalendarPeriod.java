package ua.vtc.rozklad.core.calendar;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
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
 * Holiday, practice, study mode, НС … in a semester: a {@link PeriodKind} over dates. Scope ALL / COURSES
 * ({@code courses}) / GROUPS (rows of {@code calendar_period_group}). {@code orderNote} is copied into orders
 * («Групи 4КІ з 01.09.26р. по 31.10.26 навчаються – дистанційно.»). A period of a SUSPEND kind may name its
 * supervisors (керівники, rows of {@code calendar_period_supervisor}) and a room (display only: it does not
 * occupy the room); other kinds have neither. {@code source}/{@code sourceKey} mark the rows of the academic
 * calendar import. Read the kind in the same query as the period ({@code join fetch p.kind}).
 */
@Entity
@Table(name = "calendar_period")
public class CalendarPeriod extends BaseEntity {

    @Column(name = "semester_id", nullable = false)
    private UUID semesterId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "kind_id", nullable = false)
    private PeriodKind kind;

    /** Deprecated column kept one release: written from the kind ({@link CalendarPeriodKind#of}), never read. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 20)
    private CalendarPeriodKind legacyKind;

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

    @ElementCollection
    @CollectionTable(name = "calendar_period_supervisor", joinColumns = @JoinColumn(name = "period_id"))
    @Column(name = "teacher_id", nullable = false)
    private Set<UUID> supervisorIds = new HashSet<>();

    @Column(name = "room_id")
    private UUID roomId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private PeriodSource source = PeriodSource.MANUAL;

    @Column(name = "source_key", length = 200)
    private String sourceKey;

    protected CalendarPeriod() {
    }

    public CalendarPeriod(UUID semesterId, PeriodKind kind, String title, LocalDate startsOn, LocalDate endsOn) {
        this.semesterId = Objects.requireNonNull(semesterId, "semesterId");
        setKind(kind);
        setTitle(title);
        setPeriod(startsOn, endsOn);
    }

    public UUID getSemesterId() {
        return semesterId;
    }

    public PeriodKind getKind() {
        return kind;
    }

    /** The effect of the period's kind: what it does with the lessons. */
    public PeriodEffect getEffect() {
        return kind.getEffect();
    }

    /** Sets the kind and the derived legacy column. */
    public void setKind(PeriodKind kind) {
        this.kind = Objects.requireNonNull(kind, "kind");
        syncLegacyKind();
    }

    /** Re-derives the deprecated {@code kind} column (after the kind's effect changed). */
    public void syncLegacyKind() {
        this.legacyKind = CalendarPeriodKind.of(kind);
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

    /** Teachers supervising a period of a SUSPEND kind (empty for other kinds). */
    public Set<UUID> getSupervisorIds() {
        return Collections.unmodifiableSet(supervisorIds);
    }

    /** Replaces the supervisors; only a period of a SUSPEND kind may have them. */
    public void setSupervisors(Collection<UUID> teacherIds) {
        Set<UUID> wanted = teacherIds == null ? Set.of() : Set.copyOf(teacherIds);
        if (!wanted.isEmpty() && getEffect() != PeriodEffect.SUSPEND) {
            throw new IllegalArgumentException("only a period of a SUSPEND kind has supervisors");
        }
        supervisorIds.retainAll(wanted);
        supervisorIds.addAll(wanted);
    }

    /** Room where the group is during the period (НС: ауд. 401); display only, {@code null} when none. */
    public UUID getRoomId() {
        return roomId;
    }

    /** Only a period of a SUSPEND kind has a room. */
    public void setRoomId(UUID roomId) {
        if (roomId != null && getEffect() != PeriodEffect.SUSPEND) {
            throw new IllegalArgumentException("only a period of a SUSPEND kind has a room");
        }
        this.roomId = roomId;
    }

    public PeriodSource getSource() {
        return source;
    }

    /** Key of the academic calendar import that wrote the row; kept when the dispatcher edits it. */
    public String getSourceKey() {
        return sourceKey;
    }

    /** A row written by the academic calendar import under {@code key}. */
    public void markImported(String key) {
        this.source = PeriodSource.IMPORT;
        this.sourceKey = Objects.requireNonNull(key, "key");
    }

    /** The dispatcher edited the row: the import no longer touches it (the key stays for the report). */
    public void markManual() {
        this.source = PeriodSource.MANUAL;
    }
}
