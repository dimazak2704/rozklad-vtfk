package ua.vtc.rozklad.core.timetable.internal.publication;

import java.time.LocalDate;
import java.util.UUID;

/** A template lesson a refresh may keep, update or delete (no live change refers to it). */
record ReplaceableLesson(UUID id, LocalDate date, UUID templateLessonId, boolean online, boolean roomExclusive,
                         boolean scheduled) {

    ReplaceableLesson withMode(boolean online, boolean roomExclusive) {
        return new ReplaceableLesson(id, date, templateLessonId, online, roomExclusive, scheduled);
    }
}
