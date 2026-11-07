package ua.vtc.rozklad.importer.timetable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.importer.report.Issues;
import ua.vtc.rozklad.importer.report.RowType;
import ua.vtc.rozklad.importer.text.RoomNames;
import ua.vtc.rozklad.importer.text.TextNormalizer;
import ua.vtc.rozklad.importer.timetable.SheetParse.CuratorEntry;
import ua.vtc.rozklad.importer.timetable.SheetParse.HeaderGroup;
import ua.vtc.rozklad.importer.timetable.SheetParse.RawLesson;
import ua.vtc.rozklad.importer.timetable.TimetableDraft.BellProfile;
import ua.vtc.rozklad.importer.timetable.TimetableDraft.Discipline;
import ua.vtc.rozklad.importer.timetable.TimetableDraft.Group;
import ua.vtc.rozklad.importer.timetable.TimetableDraft.Lesson;
import ua.vtc.rozklad.importer.timetable.TimetableDraft.Room;
import ua.vtc.rozklad.importer.timetable.TimetableDraft.Teacher;

/**
 * Turns raw sheet content into the draft: consolidated teachers and disciplines, canonical rooms,
 * group shifts by the pairs they use (DOMAIN §2: shift 1 = pairs 1–4; shift 2 = pairs 5–8 Mon–Thu,
 * 4–6 Fri) and warnings about clashes inside the file.
 */
final class TimetableAssembler {

    private static final String[] WEEKDAY_NAMES = {"", "понеділок", "вівторок", "середа", "четвер", "п'ятниця"};

    private final Issues issues;

    TimetableAssembler(Issues issues) {
        this.issues = issues;
    }

    TimetableDraft assemble(String sheetName, SheetParse raw, List<BellProfile> bellProfiles) {
        Map<RawLesson, TeacherRegistry.Occurrence> lessonTeachers = new IdentityHashMap<>();
        Map<CuratorEntry, TeacherRegistry.Occurrence> curatorTeachers = new IdentityHashMap<>();
        List<TeacherRegistry.Occurrence> occurrences = new ArrayList<>();
        for (CuratorEntry curator : raw.curators()) {
            var occurrence = new TeacherRegistry.Occurrence(curator.name(), curator.raw(), curator.location(), null, true);
            curatorTeachers.put(curator, occurrence);
            occurrences.add(occurrence);
        }
        for (RawLesson lesson : raw.lessons()) {
            if (lesson.teacher() != null) {
                var occurrence = new TeacherRegistry.Occurrence(lesson.teacher(), lesson.teacherRaw(), lesson.location(),
                        TextNormalizer.abbreviationKey(lesson.discipline()), false);
                lessonTeachers.put(lesson, occurrence);
                occurrences.add(occurrence);
            }
        }
        TeacherRegistry teachers = new TeacherRegistry(issues).build(occurrences);

        List<DisciplineRegistry.Occurrence> disciplineOccurrences = raw.lessons().stream()
                .map(lesson -> new DisciplineRegistry.Occurrence(lesson.discipline(),
                        lessonTeachers.containsKey(lesson) ? teachers.personOf(lessonTeachers.get(lesson)).shortName() : null,
                        lesson.location()))
                .toList();
        DisciplineRegistry disciplines = new DisciplineRegistry(issues).build(disciplineOccurrences);

        Map<String, Map<String, Integer>> roomVariants = new LinkedHashMap<>();
        List<Lesson> lessons = new ArrayList<>();
        for (RawLesson lesson : raw.lessons()) {
            String room = registerRoom(lesson.room(), roomVariants);
            String teacher = lessonTeachers.containsKey(lesson)
                    ? teachers.personOf(lessonTeachers.get(lesson)).shortName()
                    : null;
            lessons.add(new Lesson(lesson.weekday(), lesson.pair(), lesson.parity(), lesson.groupCode(), lesson.subgroup(),
                    disciplines.disciplineOf(lesson.discipline()).shortName(), teacher, room, lesson.location()));
        }
        Map<String, String> curators = new HashMap<>();
        Map<String, String> actingCurators = new HashMap<>();
        for (CuratorEntry curator : raw.curators()) {
            String shortName = teachers.personOf(curatorTeachers.get(curator)).shortName();
            (curator.acting() ? actingCurators : curators).putIfAbsent(curator.groupCode(), shortName);
        }
        List<Group> groups = new ArrayList<>();
        for (HeaderGroup header : raw.groups()) {
            String homeRoom = registerRoom(header.homeRoom(), roomVariants);
            String code = header.code().code();
            List<Lesson> groupLessons = lessons.stream().filter(lesson -> lesson.groupCode().equals(code)).toList();
            if (groupLessons.isEmpty()) {
                issues.warn(RowType.GROUP, header.raw(), header.location(), "Для групи «" + code + "» не знайдено жодного заняття.");
            }
            groups.add(new Group(code, header.raw(), header.code().course(), header.code().letters(),
                    header.code().number(), detectShift(code, groupLessons, header.location()),
                    new ArrayList<>(groupLessons.stream().map(Lesson::subgroup).filter(subgroup -> subgroup > 0)
                            .collect(java.util.stream.Collectors.toCollection(TreeSet::new))),
                    homeRoom, curators.get(code), actingCurators.get(code), header.location()));
        }
        List<Room> rooms = rooms(roomVariants, lessons);
        reportGroupClashes(lessons);
        reportTeacherClashes(lessons);
        reportRoomClashes(lessons, rooms);

        List<Teacher> teacherList = teachers.persons().stream()
                .map(person -> new Teacher(person.shortName(), person.displayName(), person.surname(),
                        person.formattedInitials().isEmpty() ? null : person.formattedInitials(), person.variants(),
                        person.lessonCount(), person.ambiguous()))
                .sorted(Comparator.comparing(Teacher::shortName))
                .toList();
        List<Discipline> disciplineList = disciplines.roots().stream()
                .map(root -> {
                    String shortName = root.shortName();
                    String fullName = root.keys().stream().map(KnownDisciplines.all()::get).filter(Objects::nonNull)
                            .findFirst().orElse(null);
                    return new Discipline(shortName, fullName == null ? shortName : fullName, fullName == null,
                            root.variants(), root.count());
                })
                .sorted(Comparator.comparing(Discipline::shortName))
                .toList();
        return new TimetableDraft(sheetName, raw.title(), raw.semesterLabel(), groups, teacherList, disciplineList, rooms,
                lessons, bellProfiles);
    }

    private static String registerRoom(String raw, Map<String, Map<String, Integer>> variants) {
        String canonical = RoomNames.canonical(raw);
        if (canonical.isEmpty()) {
            return null;
        }
        variants.computeIfAbsent(canonical, key -> new LinkedHashMap<>()).merge(TextNormalizer.clean(raw), 1, Integer::sum);
        return canonical;
    }

    private int detectShift(String code, List<Lesson> lessons, String location) {
        int firstOnly = 0;
        int secondOnly = 0;
        for (Lesson lesson : lessons) {
            boolean first = inFirstShift(lesson);
            boolean second = inSecondShift(lesson);
            if (first && !second) {
                firstOnly++;
            } else if (second && !first) {
                secondOnly++;
            }
        }
        int shift = secondOnly > firstOnly ? 2 : 1;
        long outside = lessons.stream().filter(lesson -> shift == 1 ? !inFirstShift(lesson) : !inSecondShift(lesson)).count();
        if (outside > 0) {
            issues.warn(RowType.GROUP, code, location, "Група «" + code + "» (" + shift + " зміна): " + outside
                    + " занять поза вікном своєї зміни.");
        }
        return shift;
    }

    private static boolean inFirstShift(Lesson lesson) {
        return lesson.pair() <= 4;
    }

    private static boolean inSecondShift(Lesson lesson) {
        return lesson.weekday() == 5 ? lesson.pair() >= 4 && lesson.pair() <= 6 : lesson.pair() >= 5;
    }

    private List<Room> rooms(Map<String, Map<String, Integer>> variants, List<Lesson> lessons) {
        Map<String, Integer> observed = new HashMap<>();
        Map<String, List<Lesson>> bySlot = new HashMap<>();
        for (Lesson lesson : lessons) {
            if (lesson.room() != null) {
                bySlot.computeIfAbsent(lesson.room() + "|" + lesson.weekday() + "|" + lesson.pair(), key -> new ArrayList<>())
                        .add(lesson);
            }
        }
        bySlot.forEach((key, slotLessons) -> observed.merge(slotLessons.getFirst().room(), occupancy(slotLessons), Math::max));
        List<Room> rooms = new ArrayList<>();
        variants.forEach((name, spellings) -> {
            RoomType type = RoomNames.type(name);
            int maxParallel = RoomNames.defaultMaxParallel(name);
            if (type == RoomType.GYM || type == RoomType.SPORTS_GROUND) {
                maxParallel = Math.max(maxParallel, observed.getOrDefault(name, 1));
            }
            rooms.add(new Room(name, type, maxParallel, List.copyOf(spellings.keySet())));
        });
        rooms.sort(Comparator.comparing(Room::name));
        return rooms;
    }

    /** Lessons meeting in the same week at most: numerator and denominator counted separately. */
    private static int occupancy(List<Lesson> lessons) {
        int numerator = 0;
        int denominator = 0;
        for (Lesson lesson : lessons) {
            if (lesson.parity().overlaps(Parity.NUMERATOR)) {
                numerator++;
            }
            if (lesson.parity().overlaps(Parity.DENOMINATOR)) {
                denominator++;
            }
        }
        return Math.max(numerator, denominator);
    }

    private void reportGroupClashes(List<Lesson> lessons) {
        Map<String, List<Lesson>> bySlot = new LinkedHashMap<>();
        lessons.forEach(lesson -> bySlot.computeIfAbsent(lesson.groupCode() + "|" + lesson.weekday() + "|" + lesson.pair(),
                key -> new ArrayList<>()).add(lesson));
        for (List<Lesson> slot : bySlot.values()) {
            if (anyPair(slot, (a, b) -> a.parity().overlaps(b.parity())
                    && (a.subgroup() == 0 || b.subgroup() == 0 || a.subgroup() == b.subgroup()))) {
                Lesson first = slot.getFirst();
                issues.warn(RowType.LESSON, first.groupCode(), first.location(), "Накладка: у групи «" + first.groupCode() + "» "
                        + slotLabel(first) + " одночасно кілька занять (" + describe(slot) + ").");
            }
        }
    }

    private void reportTeacherClashes(List<Lesson> lessons) {
        Map<String, List<Lesson>> bySlot = new LinkedHashMap<>();
        lessons.stream().filter(lesson -> lesson.teacher() != null)
                .forEach(lesson -> bySlot.computeIfAbsent(lesson.teacher() + "|" + lesson.weekday() + "|" + lesson.pair(),
                        key -> new ArrayList<>()).add(lesson));
        for (List<Lesson> slot : bySlot.values()) {
            List<Lesson[]> clashes = new ArrayList<>();
            for (int i = 0; i < slot.size(); i++) {
                for (int j = i + 1; j < slot.size(); j++) {
                    Lesson a = slot.get(i);
                    Lesson b = slot.get(j);
                    boolean sameCohort = a.groupCode().equals(b.groupCode()) && a.subgroup() == b.subgroup();
                    if (a.parity().overlaps(b.parity()) && !sameCohort) {
                        clashes.add(new Lesson[] {a, b});
                    }
                }
            }
            if (clashes.isEmpty()) {
                continue;
            }
            Lesson first = slot.getFirst();
            boolean stream = clashes.stream().allMatch(pair -> pair[0].discipline().equals(pair[1].discipline())
                    && Objects.equals(pair[0].room(), pair[1].room()));
            if (stream) {
                issues.info(RowType.LESSON, first.teacher(), first.location(), "Ймовірно потік: «" + first.teacher() + "» "
                        + slotLabel(first) + " веде «" + first.discipline() + "» для кількох груп в одній аудиторії ("
                        + describe(slot) + ").");
            } else {
                issues.warn(RowType.LESSON, first.teacher(), first.location(), "Накладка викладача: «" + first.teacher() + "» "
                        + slotLabel(first) + " має кілька занять одночасно (" + describe(slot) + ").");
            }
        }
    }

    private void reportRoomClashes(List<Lesson> lessons, List<Room> rooms) {
        Map<String, Integer> capacity = new HashMap<>();
        rooms.forEach(room -> capacity.put(room.name(), room.maxParallel()));
        Map<String, List<Lesson>> bySlot = new LinkedHashMap<>();
        lessons.stream().filter(lesson -> lesson.room() != null)
                .forEach(lesson -> bySlot.computeIfAbsent(lesson.room() + "|" + lesson.weekday() + "|" + lesson.pair(),
                        key -> new ArrayList<>()).add(lesson));
        for (List<Lesson> slot : bySlot.values()) {
            Lesson first = slot.getFirst();
            boolean sameTeacher = slot.stream().map(Lesson::teacher).distinct().count() == 1 && first.teacher() != null;
            if (occupancy(slot) > capacity.getOrDefault(first.room(), 1) && !sameTeacher) {
                issues.warn(RowType.LESSON, first.room(), first.location(), "Аудиторія «" + first.room() + "» "
                        + slotLabel(first) + " зайнята кількома групами (" + describe(slot) + ").");
            }
        }
    }

    private static boolean anyPair(List<Lesson> lessons, java.util.function.BiPredicate<Lesson, Lesson> test) {
        for (int i = 0; i < lessons.size(); i++) {
            for (int j = i + 1; j < lessons.size(); j++) {
                if (test.test(lessons.get(i), lessons.get(j))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String slotLabel(Lesson lesson) {
        return WEEKDAY_NAMES[lesson.weekday()] + ", " + lesson.pair() + " пара";
    }

    private static String describe(List<Lesson> lessons) {
        List<String> parts = new ArrayList<>();
        for (Lesson lesson : lessons) {
            StringBuilder part = new StringBuilder(lesson.groupCode());
            if (lesson.subgroup() > 0) {
                part.append(" п.").append(lesson.subgroup());
            }
            part.append(' ').append(lesson.discipline());
            if (lesson.parity() != Parity.BOTH) {
                part.append(lesson.parity() == Parity.NUMERATOR ? " (чис.)" : " (знам.)");
            }
            parts.add(part.toString());
        }
        return String.join("; ", parts);
    }
}
