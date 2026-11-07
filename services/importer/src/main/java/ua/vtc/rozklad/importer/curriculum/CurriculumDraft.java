package ua.vtc.rozklad.importer.curriculum;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One semester of the curriculum («навчальний план», DOMAIN §11) as recognized by AI: per
 * discipline weekly and semester hours, the discipline's lecture/practice/lab totals and the
 * control form of this semester.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CurriculumDraft(int semester, Integer weeks, List<Row> rows, Double totalHoursPerWeek, Source source) {

    public CurriculumDraft {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public enum Source {
        /** A scan or photo through the vision model. */
        AI_VISION,
        /** An XLSX table converted to text for the text model. */
        AI_TABLE
    }

    public enum ControlForm {
        EXAM, CREDIT, NONE, UNKNOWN
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Row(String discipline, Double hoursPerWeek, Integer hoursPerSemester, Integer totalHours,
                      Integer classroomHours, Integer lectures, Integer practice, Integer labs, ControlForm controlForm) {
    }
}
