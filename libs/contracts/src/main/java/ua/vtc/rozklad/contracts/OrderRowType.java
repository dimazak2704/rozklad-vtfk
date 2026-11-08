package ua.vtc.rozklad.contracts;

/**
 * Row layout of an order item, see DOMAIN.md §8. Append values only. {@code MERGE_SUBGROUPS}: the
 * subgroups of a discipline are taught together by the teacher on the right side.
 */
public enum OrderRowType {
    REPLACEMENT,
    CANCELLATION,
    ADDITIONAL,
    BLOCK,
    FINISHED,
    AS_SCHEDULED,
    ROOM_CHANGE,
    MERGE_SUBGROUPS
}
