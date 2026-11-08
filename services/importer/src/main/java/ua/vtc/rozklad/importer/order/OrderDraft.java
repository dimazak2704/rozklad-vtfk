package ua.vtc.rozklad.importer.order;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.OrderRowType;

/**
 * An order («розпорядження», DOMAIN §8) read from a DOCX or recognized from a photo: header, rows
 * of both sections as text snapshots (exactly as printed), notes and signature. {@code reviewed}:
 * the admin compared the rows with the original and saved them ({@code PUT /api/import/jobs/{id}/draft});
 * a draft recognized by AI cannot be applied before that.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderDraft(Integer number, OrderKind kind, Integer parentNumber, LocalDate date, String weekdayLabel,
                         String parityLabel, List<Row> rows, List<String> notes, String signatoryPosition,
                         String signatoryName, Source source, Boolean reviewed) {

    public OrderDraft {
        rows = rows == null ? List.of() : List.copyOf(rows);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public OrderDraft(Integer number, OrderKind kind, Integer parentNumber, LocalDate date, String weekdayLabel,
                      String parityLabel, List<Row> rows, List<String> notes, String signatoryPosition,
                      String signatoryName, Source source) {
        this(number, kind, parentNumber, date, weekdayLabel, parityLabel, rows, notes, signatoryPosition, signatoryName,
                source, null);
    }

    public boolean isReviewed() {
        return Boolean.TRUE.equals(reviewed);
    }

    public enum Section {
        CHANGES, ROOM_CHANGES
    }

    public enum Source {
        DOCX, AI
    }

    /** One table row; two subgroup names/rooms are joined with « / ». */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Row(Section section, String groupCode, String pairLabel, OrderRowType rowType, String fromDiscipline,
                      String fromTeacher, String reasonText, String toDiscipline, String toTeacher, String roomText,
                      String location) {
    }
}
