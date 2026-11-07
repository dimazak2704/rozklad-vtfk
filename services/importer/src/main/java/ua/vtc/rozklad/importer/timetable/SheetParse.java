package ua.vtc.rozklad.importer.timetable;

import java.util.List;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.importer.text.GroupCode;
import ua.vtc.rozklad.importer.text.PersonName;

/** Raw content of the timetable sheet before names are consolidated. */
record SheetParse(String title, String semesterLabel, List<HeaderGroup> groups, List<RawLesson> lessons,
                  List<CuratorEntry> curators) {

    /** A group column block: discipline in {@code column}, teacher in +1, room in +2. */
    record HeaderGroup(GroupCode code, String raw, int column, String homeRoom, String location) {
    }

    /** A lesson exactly as the cells say; names are cleaned but not yet merged. */
    record RawLesson(int weekday, int pair, Parity parity, String groupCode, int subgroup, String discipline,
                     PersonName teacher, String teacherRaw, String room, String location) {
    }

    /** A curator from the row above the group codes; {@code acting} = the name in parentheses. */
    record CuratorEntry(PersonName name, String raw, String groupCode, boolean acting, String location) {
    }
}
