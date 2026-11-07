package ua.vtc.rozklad.importer.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.util.List;

/**
 * Wire shapes of the structured outputs (see {@link AiSchemas}). Enum-like values stay strings
 * here; handlers validate and convert them, so a surprising value becomes a report warning instead
 * of a lost answer.
 */
public final class AiExtractions {

    private AiExtractions() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderExtraction(Integer number, String kind, Integer parentNumber, String date, String weekdayLabel,
                                  String parityLabel, List<OrderRow> rows, List<String> notes, String signatoryPosition,
                                  String signatoryName, List<String> warnings) {

        public OrderExtraction {
            rows = rows == null ? List.of() : List.copyOf(rows);
            notes = notes == null ? List.of() : List.copyOf(notes);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderRow(String section, String groupCode, String pairLabel, String rowType, String fromDiscipline,
                           String fromTeacher, String reasonText, String toDiscipline, String toTeacher, String roomText) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CurriculumExtraction(Integer semester, Integer weeks, List<CurriculumRow> rows, Double totalHoursPerWeek,
                                       List<String> warnings) {

        public CurriculumExtraction {
            rows = rows == null ? List.of() : List.copyOf(rows);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CurriculumRow(String discipline, Double hoursPerWeek, Integer hoursPerSemester, Integer totalHours,
                                Integer classroomHours, Integer lectures, Integer practice, Integer labs,
                                String controlForm) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProgramHoursExtraction(String disciplineName, Integer totalHours, Integer classroomHours,
                                         Integer selfStudyHours, Integer lectures, Integer practice, Integer labs,
                                         Integer seminars, boolean exam, boolean credit, Integer semester,
                                         Double hoursPerWeek, List<String> warnings) {

        public ProgramHoursExtraction {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AbsenceExtraction(LocalDate dateFrom, LocalDate dateTo, List<Integer> pairs, String reasonCategory,
                                    double confidence, String clarification) {
    }
}
