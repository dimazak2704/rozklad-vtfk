package ua.vtc.rozklad.importer.apply;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;

/**
 * Bodies of core's import endpoints (CONTRACTS §2 «Import apply»). Everything references other
 * entities by natural keys (group code, teacher short name, discipline short name, room name,
 * specialty letters), so core can upsert in one transaction.
 */
public final class CoreImportPayloads {

    private CoreImportPayloads() {
    }

    /** {@code POST /api/admin/import/catalog} — upsert by natural keys. */
    public record CatalogRequest(List<Department> departments, List<Specialty> specialties, List<Teacher> teachers,
                                 List<Discipline> disciplines, List<Room> rooms, List<Group> groups,
                                 List<Alias> aliases) {

        public boolean isEmpty() {
            return departments.isEmpty() && specialties.isEmpty() && teachers.isEmpty() && disciplines.isEmpty()
                    && rooms.isEmpty() && groups.isEmpty() && aliases.isEmpty();
        }
    }

    public record Department(String name, String shortName) {
    }

    /** {@code name} is the letters when the full specialty name is unknown (imported data lacks it). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Specialty(String letters, String name, String code, String departmentName) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Teacher(String shortName, String displayName, String lastName, String firstName, String middleName,
                          boolean needsReview) {
    }

    public record Discipline(String shortName, String name, boolean needsReview) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Room(String name, RoomType type, int maxParallel, Integer capacity) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Group(String code, int course, String specialtyLetters, Integer number, int shift,
                        String curatorShortName, String homeRoomName) {
    }

    /** {@code type} TEACHER or DISCIPLINE; {@code targetShortName} — short name of the existing/new entity. */
    public record Alias(String type, String alias, String targetShortName) {
    }

    /** {@code POST /api/admin/import/timetable} → a DRAFT version. */
    public record TimetableRequest(UUID semesterId, String name, List<TimetableLesson> lessons) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TimetableLesson(int weekday, int pair, Parity parity, String groupCode, int subgroup,
                                  String disciplineShortName, String teacherShortName, String roomName, boolean online) {
    }

    /** {@code POST /api/admin/import/load}. */
    public record LoadRequest(UUID semesterId, List<LoadItem> items) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LoadItem(String groupCode, String disciplineShortName, LessonKind lessonKind, BigDecimal hoursPerWeek,
                           int plannedHours, String controlForm, String source, String note) {
    }

    /** {@code POST /api/admin/import/changes} — a historic order as text snapshots. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChangesRequest(LocalDate date, Integer number, OrderKind kind, Integer parentNumber,
                                 String weekdayLabel, String parityLabel, List<ChangeItem> items, List<String> notes,
                                 String signatoryPosition, String signatoryName) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChangeItem(String section, String groupCode, String pairLabel, OrderRowType rowType,
                             String fromDiscipline, String fromTeacher, String reasonText, String toDiscipline,
                             String toTeacher, String roomText) {
    }
}
