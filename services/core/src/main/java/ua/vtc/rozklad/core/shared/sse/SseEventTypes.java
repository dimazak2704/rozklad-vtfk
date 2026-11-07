package ua.vtc.rozklad.core.shared.sse;

/** Event names of {@code GET /api/events/stream} (CONTRACTS.md §2); data is JSON of the related DTO. */
public final class SseEventTypes {

    public static final String CHANGE_UPDATED = "change.updated";
    public static final String ABSENCE_CREATED = "absence.created";
    public static final String GENERATION_PROGRESS = "generation.progress";
    public static final String GENERATION_COMPLETED = "generation.completed";
    public static final String ORDER_PUBLISHED = "order.published";
    public static final String SCHEDULE_CHANGED = "schedule.changed";
    public static final String PING = "ping";

    private SseEventTypes() {
    }
}
