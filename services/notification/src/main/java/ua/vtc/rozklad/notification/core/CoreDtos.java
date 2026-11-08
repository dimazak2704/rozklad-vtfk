package ua.vtc.rozklad.notification.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.ConfirmationDecision;
import ua.vtc.rozklad.contracts.Parity;

/**
 * Read models of core's public/internal REST (CONTRACTS §1, §3, §4). Only the fields the bot uses;
 * unknown fields are ignored so core can grow its DTOs.
 */
public final class CoreDtos {

    private CoreDtos() {
    }

    /**
     * {@code GET /api/public/meta}. Nullable: semester, weekNumber, parity (outside a semester), pairs,
     * nextStudyDay (none known ahead, or an older core).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PublicMeta(String collegeName, SemesterInfo semester, LocalDate today, Integer weekNumber,
                             Parity parity, boolean studyDay, PairNow currentPair, PairNow nextPair,
                             NamedRef bellProfile, StudyDay nextStudyDay) {
    }

    /** The first study day after today with its week (core skips weekends and holidays). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StudyDay(LocalDate date, Integer weekday, Integer weekNumber, Parity parity) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SemesterInfo(UUID id, String name, LocalDate startsOn, LocalDate endsOn) {
    }

    /** Current or next pair; {@code minutesLeft} is set for the current one. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PairNow(int number, LocalTime startsAt, LocalTime endsAt, Integer minutesLeft) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NamedRef(UUID id, String name) {
    }

    /** {@code GET /api/public/groups} item. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PublicGroup(UUID id, String code, int course, Integer shift) {
    }

    /** {@code GET /api/public/teachers} item. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PublicTeacher(UUID id, String shortName, String displayName) {

        public String label() {
            if (shortName != null && !shortName.isBlank()) {
                return shortName;
            }
            return displayName == null ? "" : displayName;
        }
    }

    /** {@code GET /api/public/search} item; {@code type} is GROUP, TEACHER or ROOM. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchHit(String type, UUID id, String label, String sublabel, String url) {

        public OwnerType ownerType() {
            return OwnerType.fromSearchType(type);
        }
    }

    /** {@code GET /api/public/bells?date=}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BellSchedule(NamedRef profile, Integer weekday, List<BellSlot> slots) {

        public BellSchedule {
            slots = slots == null ? List.of() : List.copyOf(slots);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BellSlot(int pair, LocalTime startsAt, LocalTime endsAt) {
    }

    /** {@code GET /api/public/schedule/{type}/{id}?from=&to=}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Schedule(ScheduleOwner owner, LocalDate from, LocalDate to, List<ScheduleDay> days) {

        public Schedule {
            days = days == null ? List.of() : List.copyOf(days);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScheduleOwner(String type, UUID id, String label) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScheduleDay(LocalDate date, int weekday, String weekdayLabel, Parity parity, boolean studyDay,
                              String note, List<LessonItem> lessons) {

        public ScheduleDay {
            lessons = lessons == null ? List.of() : List.copyOf(lessons);
        }
    }

    /**
     * {@code LessonView}. {@code kind}: REGULAR, SUBSTITUTED, CANCELLED, ADDITIONAL, MOVED,
     * ROOM_CHANGED, BLOCK (kept as text so a new kind does not break the bot). {@code original}
     * holds what was replaced; its members may be refs or plain text.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LessonItem(UUID id, int pair, LocalTime startsAt, LocalTime endsAt, DisciplineRef discipline,
                             TeacherRef teacher, RoomRef room, GroupRef group, int subgroup, String kind,
                             boolean online, JsonNode original, String reasonText, UUID changeId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DisciplineRef(UUID id, String shortName, String name, String color) {

        public String label() {
            return shortName != null && !shortName.isBlank() ? shortName : name;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TeacherRef(UUID id, String shortName, String displayName) {

        public String label() {
            return shortName != null && !shortName.isBlank() ? shortName : displayName;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RoomRef(UUID id, String name, String type) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GroupRef(UUID id, String code, Integer course) {
    }

    /** {@code GET /api/me} (called with the user's own token). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CurrentUser(UUID userId, String username, List<String> roles, PublicTeacher teacher) {
    }

    /**
     * Subset of {@code ChangeDto} returned by {@code POST /api/internal/changes/{id}/reply}: the status after
     * the answer, {@code approvalMode} ({@code AUTO|CONFIRM|NOTIFY}) and the recorded {@code teacherAnswer}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChangeSummary(UUID id, ChangeStatus status, String approvalMode, ConfirmationDecision teacherAnswer) {

        public ChangeSummary(UUID id, ChangeStatus status) {
            this(id, status, null, null);
        }

        /** The change is a NOTIFY assignment (the teacher was informed, not asked). */
        public boolean notice() {
            return "NOTIFY".equals(approvalMode);
        }
    }
}
