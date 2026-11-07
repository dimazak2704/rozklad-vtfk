package ua.vtc.rozklad.importer.timetable;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalTime;
import java.util.List;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;

/**
 * Result of parsing the college timetable workbook (DOMAIN §11): catalog entities with canonical
 * names and the weekly template lessons that reference them by natural keys (group code, teacher
 * short name, discipline short name, room name).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TimetableDraft(String sheet, String title, String semesterLabel, List<Group> groups,
                             List<Teacher> teachers, List<Discipline> disciplines, List<Room> rooms,
                             List<Lesson> lessons, List<BellProfile> bellProfiles) {

    public TimetableDraft {
        groups = List.copyOf(groups);
        teachers = List.copyOf(teachers);
        disciplines = List.copyOf(disciplines);
        rooms = List.copyOf(rooms);
        lessons = List.copyOf(lessons);
        bellProfiles = List.copyOf(bellProfiles);
    }

    /**
     * @param raw           the code as written («ІКІ1», «1ТК 1»)
     * @param subgroups     subgroup numbers used by lessons (empty = never split)
     * @param curator       short name of the curator teacher, if the curators row names one
     * @param actingCurator a second name in parentheses («Ганза А.І.(Петрова В.)»)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Group(String code, String raw, int course, String specialtyLetters, Integer number, int shift,
                        List<Integer> subgroups, String homeRoom, String curator, String actingCurator,
                        String location) {

        public Group {
            subgroups = List.copyOf(subgroups);
        }
    }

    /**
     * @param shortName unique natural key: «Харченко О.А.» when initials are known, else as written
     * @param variants  every spelling found in the file (typos included)
     */
    public record Teacher(String shortName, String displayName, String lastName, String initials,
                          List<String> variants, int lessonCount, boolean needsReview) {

        public Teacher {
            variants = List.copyOf(variants);
        }
    }

    /**
     * @param name        full name when the abbreviation is known for sure (DOMAIN §10), otherwise
     *                    the abbreviation itself with {@code needsReview}
     */
    public record Discipline(String shortName, String name, boolean needsReview, List<String> variants,
                             int lessonCount) {

        public Discipline {
            variants = List.copyOf(variants);
        }
    }

    public record Room(String name, RoomType type, int maxParallel, List<String> variants) {

        public Room {
            variants = List.copyOf(variants);
        }
    }

    /** One template lesson; {@code subgroup} 0 = whole group. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Lesson(int weekday, int pair, Parity parity, String groupCode, int subgroup, String discipline,
                         String teacher, String room, String location) {
    }

    public record BellProfile(String name, List<BellSlot> slots) {

        public BellProfile {
            slots = List.copyOf(slots);
        }
    }

    public record BellSlot(int weekday, int pair, LocalTime startsAt, LocalTime endsAt) {
    }
}
