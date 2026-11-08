package ua.vtc.rozklad.contracts.events;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * RabbitMQ topology shared by all services: exchange names, routing keys (= event names from
 * CONTRACTS.md §7), consumer queues and the keys each queue is bound to.
 */
public final class RozkladEvents {

    /** Topic exchange for all integration events (durable). */
    public static final String EXCHANGE = "rozklad.events";

    /** Dead-letter exchange (direct). A queue {@code q} dead-letters with routing key {@code q} to {@code q.dlq}. */
    public static final String DEAD_LETTER_EXCHANGE = "rozklad.dlx";

    public static final int SCHEMA_VERSION = 1;

    public static final String TIMETABLE_VERSION_PUBLISHED = "timetable.version.published";
    public static final String SCHEDULE_CHANGED = "schedule.changed";
    public static final String GENERATION_REQUESTED = "generation.requested";
    public static final String GENERATION_CANCEL = "generation.cancel";
    public static final String GENERATION_PROGRESS = "generation.progress";
    public static final String GENERATION_COMPLETED = "generation.completed";
    public static final String SUBSTITUTION_CONFIRMATION_REQUESTED = "substitution.confirmation-requested";
    public static final String SUBSTITUTION_STATUS_CHANGED = "substitution.status-changed";
    public static final String SUBSTITUTION_TEACHER_REPLIED = "substitution.teacher-replied";
    public static final String ABSENCE_REPORTED = "absence.reported";
    public static final String ORDER_PUBLISHED = "order.published";

    private static final Map<Class<? extends RozkladEvent>, String> ROUTING_KEYS = Map.ofEntries(
            Map.entry(TimetableVersionPublished.class, TIMETABLE_VERSION_PUBLISHED),
            Map.entry(ScheduleChanged.class, SCHEDULE_CHANGED),
            Map.entry(GenerationRequested.class, GENERATION_REQUESTED),
            Map.entry(GenerationCancelRequested.class, GENERATION_CANCEL),
            Map.entry(GenerationProgress.class, GENERATION_PROGRESS),
            Map.entry(GenerationCompleted.class, GENERATION_COMPLETED),
            Map.entry(SubstitutionConfirmationRequested.class, SUBSTITUTION_CONFIRMATION_REQUESTED),
            Map.entry(SubstitutionStatusChanged.class, SUBSTITUTION_STATUS_CHANGED),
            Map.entry(SubstitutionTeacherReplied.class, SUBSTITUTION_TEACHER_REPLIED),
            Map.entry(AbsenceReported.class, ABSENCE_REPORTED),
            Map.entry(OrderPublished.class, ORDER_PUBLISHED));

    private static final Map<String, Class<? extends RozkladEvent>> TYPES = ROUTING_KEYS.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));

    private RozkladEvents() {
    }

    public static String routingKey(RozkladEvent event) {
        return routingKey(Objects.requireNonNull(event, "event").getClass());
    }

    public static String routingKey(Class<? extends RozkladEvent> type) {
        String key = ROUTING_KEYS.get(type);
        if (key == null) {
            throw new IllegalArgumentException("No routing key for " + type.getName());
        }
        return key;
    }

    /** Event record class for a routing key, e.g. to deserialize a raw message. */
    public static Class<? extends RozkladEvent> eventType(String routingKey) {
        Class<? extends RozkladEvent> type = TYPES.get(routingKey);
        if (type == null) {
            throw new IllegalArgumentException("Unknown routing key: " + routingKey);
        }
        return type;
    }

    /** Consumer queues (durable, one per consuming service) and their bindings. */
    public static final class Queues {

        public static final String SCHEDULER_GENERATION = "scheduler.q.generation";
        public static final String CORE_SCHEDULER_RESULTS = "core.q.scheduler-results";
        public static final String NOTIFICATION_EVENTS = "notification.q.events";

        public static final String DEAD_LETTER_SUFFIX = ".dlq";

        private static final Map<String, List<String>> BINDINGS = Map.of(
                SCHEDULER_GENERATION, List.of(GENERATION_REQUESTED, GENERATION_CANCEL),
                CORE_SCHEDULER_RESULTS, List.of(GENERATION_PROGRESS, GENERATION_COMPLETED),
                NOTIFICATION_EVENTS, List.of(TIMETABLE_VERSION_PUBLISHED, SCHEDULE_CHANGED,
                        SUBSTITUTION_CONFIRMATION_REQUESTED, SUBSTITUTION_STATUS_CHANGED, SUBSTITUTION_TEACHER_REPLIED,
                        ABSENCE_REPORTED, ORDER_PUBLISHED, GENERATION_COMPLETED));

        private Queues() {
        }

        public static String deadLetterQueue(String queue) {
            return queue + DEAD_LETTER_SUFFIX;
        }

        /** Routing keys the queue is bound to on {@link #EXCHANGE}. */
        public static List<String> bindings(String queue) {
            List<String> keys = BINDINGS.get(queue);
            if (keys == null) {
                throw new IllegalArgumentException("Unknown queue: " + queue);
            }
            return keys;
        }

        static Map<String, List<String>> all() {
            return BINDINGS;
        }
    }

    static Map<Class<? extends RozkladEvent>, String> routingKeys() {
        return ROUTING_KEYS;
    }
}
