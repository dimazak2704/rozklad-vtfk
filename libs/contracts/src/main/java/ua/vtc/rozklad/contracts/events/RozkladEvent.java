package ua.vtc.rozklad.contracts.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Common envelope of every integration event. Consumers deduplicate by {@link #eventId()}.
 */
public sealed interface RozkladEvent
        permits TimetableVersionPublished, ScheduleChanged, GenerationRequested, GenerationCancelRequested,
        GenerationProgress, GenerationCompleted, SubstitutionConfirmationRequested, SubstitutionStatusChanged,
        SubstitutionTeacherReplied, AbsenceReported, OrderPublished {

    UUID eventId();

    Instant occurredAt();

    /** Payload schema version, currently {@value RozkladEvents#SCHEMA_VERSION}. */
    int version();
}
