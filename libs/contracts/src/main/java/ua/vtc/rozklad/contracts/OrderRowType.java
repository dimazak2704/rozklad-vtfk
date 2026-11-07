package ua.vtc.rozklad.contracts;

/** Row layout of an order item, see DOMAIN.md §8. */
public enum OrderRowType {
    REPLACEMENT,
    CANCELLATION,
    ADDITIONAL,
    BLOCK,
    FINISHED,
    AS_SCHEDULED,
    ROOM_CHANGE
}
