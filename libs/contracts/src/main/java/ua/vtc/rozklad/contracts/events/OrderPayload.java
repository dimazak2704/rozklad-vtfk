package ua.vtc.rozklad.contracts.events;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.contracts.OrderStatus;

/**
 * Full snapshot of an order (розпорядження): all fields of core's {@code OrderDto} plus the data
 * notification needs for distribution. Also the request body of
 * {@code POST /api/documents/orders/render}. Nullable: number (drafts), parentId (main orders).
 */
public record OrderPayload(
        UUID id,
        Integer number,
        LocalDate date,
        OrderKind kind,
        UUID parentId,
        OrderStatus status,
        String title,
        String weekdayLabel,
        String parityLabel,
        List<Item> items,
        List<Item> roomChanges,
        List<String> notes,
        String signatoryPosition,
        String signatoryName,
        String semesterName,
        List<AffectedGroup> affectedGroups,
        List<AffectedTeacher> affectedTeachers) {

    public OrderPayload {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        items = EventLists.copy(items);
        roomChanges = EventLists.copy(roomChanges);
        notes = EventLists.copy(notes);
        affectedGroups = EventLists.copy(affectedGroups);
        affectedTeachers = EventLists.copy(affectedTeachers);
    }

    /** Denormalized order row; text fields are printed as is. {@code changeId} may be {@code null}. */
    public record Item(
            UUID id,
            String groupCode,
            String pairLabel,
            OrderRowType rowType,
            String fromDiscipline,
            String fromTeacher,
            String reasonText,
            String toDiscipline,
            String toTeacher,
            String roomText,
            UUID changeId) {

        public Item {
            Objects.requireNonNull(rowType, "rowType");
        }
    }

    public record AffectedGroup(UUID groupId, String code) {
    }

    public record AffectedTeacher(UUID teacherId, String name) {
    }
}
