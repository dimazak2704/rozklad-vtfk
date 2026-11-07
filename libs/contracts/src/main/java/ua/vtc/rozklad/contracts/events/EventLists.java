package ua.vtc.rozklad.contracts.events;

import java.util.List;

final class EventLists {

    private EventLists() {
    }

    /** Immutable copy; {@code null} becomes an empty list. */
    static <T> List<T> copy(List<T> source) {
        return source == null ? List.of() : List.copyOf(source);
    }

    /** Immutable copy that keeps {@code null} when it carries meaning (e.g. "all pairs"). */
    static <T> List<T> copyNullable(List<T> source) {
        return source == null ? null : List.copyOf(source);
    }
}
