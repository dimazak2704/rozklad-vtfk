package ua.vtc.rozklad.core.timetable.internal.schedule;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Minimal iCalendar (RFC 5545) writer: UTC times, escaped TEXT values, CRLF line ends and lines
 * folded at 75 octets of UTF-8 (Cyrillic letters take two octets).
 */
final class IcsCalendarWriter {

    static final String CRLF = "\r\n";
    private static final int MAX_LINE_OCTETS = 75;
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private IcsCalendarWriter() {
    }

    static String write(String calendarName, List<Event> events) {
        List<String> lines = new ArrayList<>(List.of(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//VTFK//Rozklad//UK",
                "CALSCALE:GREGORIAN",
                "METHOD:PUBLISH",
                "X-WR-CALNAME:" + escape(calendarName),
                "X-WR-TIMEZONE:Europe/Kyiv",
                "REFRESH-INTERVAL;VALUE=DURATION:PT6H",
                "X-PUBLISHED-TTL:PT6H"));
        for (Event event : events) {
            lines.add("BEGIN:VEVENT");
            lines.add("UID:" + event.uid());
            lines.add("DTSTAMP:" + UTC.format(event.stamp()));
            lines.add("DTSTART:" + UTC.format(event.start()));
            lines.add("DTEND:" + UTC.format(event.end()));
            lines.add("SUMMARY:" + escape(event.summary()));
            if (event.location() != null) {
                lines.add("LOCATION:" + escape(event.location()));
            }
            if (event.description() != null) {
                lines.add("DESCRIPTION:" + escape(event.description()));
            }
            lines.add("STATUS:CONFIRMED");
            lines.add("TRANSP:OPAQUE");
            lines.add("END:VEVENT");
        }
        lines.add("END:VCALENDAR");
        StringBuilder calendar = new StringBuilder();
        lines.forEach(line -> calendar.append(fold(line)).append(CRLF));
        return calendar.toString();
    }

    /** TEXT escaping of RFC 5545 §3.3.11: backslash, semicolon, comma and line breaks. */
    static String escape(String text) {
        return text.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n");
    }

    /** Folds a content line so that no physical line exceeds 75 octets; never splits a character. */
    static String fold(String line) {
        StringBuilder folded = new StringBuilder();
        int octets = 0;
        int index = 0;
        while (index < line.length()) {
            int codePoint = line.codePointAt(index);
            String character = new String(Character.toChars(codePoint));
            int size = character.getBytes(StandardCharsets.UTF_8).length;
            if (octets + size > MAX_LINE_OCTETS) {
                folded.append(CRLF).append(' ');
                octets = 1;
            }
            folded.append(character);
            octets += size;
            index += Character.charCount(codePoint);
        }
        return folded.toString();
    }

    /** One lesson as a calendar event; {@code stamp} is the last change of the lesson (stable output). */
    record Event(String uid, Instant start, Instant end, Instant stamp, String summary, String location,
                 String description) {

        Event {
            Objects.requireNonNull(uid, "uid");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            Objects.requireNonNull(stamp, "stamp");
            Objects.requireNonNull(summary, "summary");
        }
    }
}
