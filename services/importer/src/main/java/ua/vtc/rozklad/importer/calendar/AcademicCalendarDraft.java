package ua.vtc.rozklad.importer.calendar;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import ua.vtc.rozklad.importer.apply.CoreImportPayloads;

/**
 * The academic calendar («робочий графік освітнього процесу») of one year. The file's part is fixed at parse:
 * {@code groups}, {@code legend} and {@code runs} (equal adjacent codes of a group, Monday to Friday). The rest
 * depends on core's catalog and the job's options and is rebuilt with every report: {@code codes} with their
 * kinds, {@code periods} and {@code groupSemesters} exactly as sent to core, and {@code check} — core's last
 * dry-run answer ({@code null} when it was not asked or could not be reached).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AcademicCalendarDraft(String academicYear, String sheet, List<Group> groups, List<Code> codes,
                                    List<LegendEntry> legend, List<Run> runs, List<Period> periods,
                                    List<CoreImportPayloads.GroupSemester> groupSemesters, JsonNode check) {

    public AcademicCalendarDraft {
        groups = groups == null ? List.of() : List.copyOf(groups);
        codes = codes == null ? List.of() : List.copyOf(codes);
        legend = legend == null ? List.of() : List.copyOf(legend);
        runs = runs == null ? List.of() : List.copyOf(runs);
        periods = periods == null ? List.of() : List.copyOf(periods);
        groupSemesters = groupSemesters == null ? List.of() : List.copyOf(groupSemesters);
    }

    /**
     * A group row: {@code row} is the Excel row (from 1); {@code theoryWeeks} and {@code exams} hold semesters I
     * and II ({@code null} for an empty cell).
     */
    public record Group(String raw, String code, int row, List<Integer> theoryWeeks, List<Integer> exams) {

        public Group {
            theoryWeeks = theoryWeeks == null ? List.of() : new ArrayList<>(theoryWeeks);
            exams = exams == null ? List.of() : new ArrayList<>(exams);
        }
    }

    /** A code found in the cells; the kind fields are {@code null} until matched (or when no kind has it). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Code(String code, String kindCode, String kindName, String effect, int cells) {
    }

    /** A legend line: code, its fill colour {@code #RRGGBB} (or {@code null}) and text in sentence case. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LegendEntry(String code, String color, String title) {
    }

    /**
     * One run of equal codes of a group: {@code title} is the legend's title for the run's colour when the legend
     * tells variants of the code apart by colour («НП»), else {@code null}; {@code cells} like «T61:U61».
     */
    public record Run(String groupCode, String code, String title, LocalDate startsOn, LocalDate endsOn, int weeks,
                      String cells) {
    }

    /** An item of core's request (shared contract §3) plus the cells it came from. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Period(String sourceKey, String kindCode, String title, LocalDate startsOn, LocalDate endsOn,
                         String scope, List<String> groupCodes, List<Integer> courses, String roomName, String studyMode,
                         String orderNote, String cells) {

        public CoreImportPayloads.CalendarPeriod payload() {
            return new CoreImportPayloads.CalendarPeriod(sourceKey, kindCode, title, startsOn, endsOn, scope, groupCodes,
                    courses, roomName, studyMode, orderNote);
        }
    }

    AcademicCalendarDraft withDerived(List<Code> codes, List<Period> periods,
                                      List<CoreImportPayloads.GroupSemester> groupSemesters, JsonNode check) {
        return new AcademicCalendarDraft(academicYear, sheet, groups, codes, legend, runs, periods, groupSemesters, check);
    }
}
