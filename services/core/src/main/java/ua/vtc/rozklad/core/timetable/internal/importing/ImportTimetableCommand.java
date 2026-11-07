package ua.vtc.rozklad.core.timetable.internal.importing;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.Parity;

/** Timetable recognized by the importer, with names instead of ids (CONTRACTS.md §2 «Import apply»). */
public record ImportTimetableCommand(UUID semesterId, String name, List<Lesson> lessons) {

    public ImportTimetableCommand {
        Objects.requireNonNull(semesterId, "semesterId");
        Objects.requireNonNull(name, "name");
        lessons = List.copyOf(lessons);
    }

    /** Nullable: teacherShortName, roomName. */
    public record Lesson(int weekday, int pair, Parity parity, String groupCode, int subgroup,
                         String disciplineShortName, String teacherShortName, String roomName, boolean online) {
    }
}
