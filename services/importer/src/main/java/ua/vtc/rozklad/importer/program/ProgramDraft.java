package ua.vtc.rozklad.importer.program;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Hours of a discipline program (DOMAIN §11). Values are what the document says; nothing is
 * computed or "fixed" — inconsistencies become report warnings.
 *
 * @param disciplineName name from the title page in sentence case («Економіка та управління ІТ-проєктами»)
 * @param semester       semester printed in the program — informational only, it is often outdated
 * @param source         where the hours come from: the structure table, PDF text or the AI fallback
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProgramDraft(String disciplineName, String disciplineRaw, String cycleCommission, Hours hours,
                           Integer semester, Double hoursPerWeek, Double credits, Source source, String location) {

    public enum Source {
        TABLE, PDF_TEXT, AI
    }

    /** Totals of the structure table; {@code null} = the cell is empty or «-». */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Hours(Integer total, Integer classroom, Integer selfStudy, Integer lectures, Integer practice,
                        Integer labs, Integer seminars, Integer consultations, boolean exam, boolean credit) {

        /** Lectures + practice + labs + seminars (empty cells count as 0). */
        public int classroomSum() {
            return value(lectures) + value(practice) + value(labs) + value(seminars);
        }

        static int value(Integer hours) {
            return hours == null ? 0 : hours;
        }
    }
}
