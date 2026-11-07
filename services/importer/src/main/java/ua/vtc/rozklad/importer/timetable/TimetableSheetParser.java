package ua.vtc.rozklad.importer.timetable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.util.CellRangeAddress;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.importer.report.Issues;
import ua.vtc.rozklad.importer.report.RowType;
import ua.vtc.rozklad.importer.text.GroupCode;
import ua.vtc.rozklad.importer.text.PersonName;
import ua.vtc.rozklad.importer.text.TextNormalizer;
import ua.vtc.rozklad.importer.timetable.SheetParse.CuratorEntry;
import ua.vtc.rozklad.importer.timetable.SheetParse.HeaderGroup;
import ua.vtc.rozklad.importer.timetable.SheetParse.RawLesson;

/**
 * Reads the print layout of the college timetable (DOMAIN §11). Structure is found by content,
 * not by fixed rows or colours: a group header row has at least three group codes (curators are
 * in the row above, the home room in each group's third column); day blocks are labelled in
 * column A, pair numbers are in column B and every pair has two rows. The two rows of a group's
 * three cells [discipline, teacher, room] are decoded through merged ranges:
 * <ul>
 *   <li>discipline merged over both rows — every week; one teacher → BOTH, two different
 *       teachers → subgroups 1 (top) and 2 (bottom);</li>
 *   <li>not merged — the top row is the numerator, the bottom row the denominator;</li>
 *   <li>«Крисак Л./Можаровська» with «110/12» in one cell — subgroups 1 and 2;</li>
 *   <li>«(п)» after a teacher — a lesson of one subgroup only.</li>
 * </ul>
 */
final class TimetableSheetParser {

    private static final Pattern PAIR_NUMBER = Pattern.compile("^([1-8])\\s*\\.?$");
    private static final Map<String, Integer> WEEKDAYS = Map.of(
            "понеділок", 1, "вівторок", 2, "середа", 3, "четвер", 4, "п'ятниця", 5, "пятниця", 5);
    // «Укр.мов.Зоріна», «Матем.Овчар Ін.» — прізвище записано в клітинці дисципліни
    private static final Pattern EMBEDDED_TEACHER =
            Pattern.compile("^(.+\\.)\\s*([А-ЯІЇЄҐ][а-яіїєґ'’ʼ]{3,}(?:\\s+[А-ЯІЇЄҐ][а-яіїєґ]?\\.)*)$");
    private static final Pattern CURATOR_WITH_ACTING = Pattern.compile("^(.*?)\\s*\\((.+)\\)\\s*$");
    private static final int MIN_GROUPS_IN_HEADER = 3;

    private final SheetGrid grid;
    private final Issues issues;
    private final List<HeaderGroup> groups = new ArrayList<>();
    private final List<RawLesson> lessons = new ArrayList<>();
    private final List<CuratorEntry> curators = new ArrayList<>();

    TimetableSheetParser(SheetGrid grid, Issues issues) {
        this.grid = grid;
        this.issues = issues;
    }

    SheetParse parse() {
        List<Header> headers = findHeaders();
        if (headers.isEmpty()) {
            issues.error(RowType.FILE, grid.sheetName(), grid.sheetName(),
                    "На аркуші не знайдено рядка з кодами груп (наприклад «1КІ1 … 1КІ2 … 1КІ3»).");
            return new SheetParse(null, null, List.of(), List.of(), List.of());
        }
        Set<String> seenCodes = new HashSet<>();
        for (int i = 0; i < headers.size(); i++) {
            Header header = headers.get(i);
            int lastRow = i + 1 < headers.size() ? headers.get(i + 1).row() - 2 : grid.lastRow();
            List<HeaderGroup> blockGroups = new ArrayList<>();
            for (HeaderGroup group : header.groups()) {
                if (!seenCodes.add(group.code().code())) {
                    issues.error(RowType.GROUP, group.raw(), group.location(),
                            "Група «" + group.code() + "» трапляється на аркуші вдруге — її другий блок пропущено.");
                    continue;
                }
                blockGroups.add(group);
            }
            groups.addAll(blockGroups);
            parseBlock(header, blockGroups, header.row() + 1, lastRow);
        }
        return new SheetParse(findTitle(headers.getFirst().row()), findSemesterLabel(headers.getFirst().row()),
                groups, lessons, curators);
    }

    // ───────────────────────── заголовки груп ─────────────────────────

    private record Header(int row, int course, List<HeaderGroup> groups) {
    }

    private List<Header> findHeaders() {
        List<Header> headers = new ArrayList<>();
        for (int row = 0; row <= grid.lastRow(); row++) {
            List<HeaderGroup> candidates = new ArrayList<>();
            int startingWithDigit = 0;
            for (int column = 2; column <= grid.lastColumn(); column++) {
                if (!ownsCell(row, column)) {
                    continue;
                }
                String text = grid.text(row, column);
                Optional<GroupCode> code = GroupCode.parse(text);
                if (code.isEmpty()) {
                    continue;
                }
                if (Character.isDigit(text.charAt(0))) {
                    startingWithDigit++;
                }
                String homeRoom = grid.text(row, column + 2);
                candidates.add(new HeaderGroup(code.get(), text, column, homeRoom.isEmpty() ? null : homeRoom,
                        grid.location(row, column)));
            }
            // більшість кодів має починатися з цифри: так рядок занять з «ІГ», «ІКТ» не стане заголовком
            if (candidates.size() >= MIN_GROUPS_IN_HEADER && startingWithDigit * 5 >= candidates.size() * 3) {
                headers.add(new Header(row, majorityCourse(candidates), candidates));
            }
        }
        for (Header header : headers) {
            for (HeaderGroup group : header.groups()) {
                if (group.code().course() != header.course()) {
                    issues.warn(RowType.GROUP, group.raw(), group.location(), "Група «" + group.code()
                            + "» стоїть у блоці " + header.course() + " курсу — перевірте код групи.");
                }
                if (GroupCode.wasCorrected(group.raw(), group.code())) {
                    issues.info(RowType.GROUP, group.raw(), group.location(),
                            "Код групи «" + group.raw() + "» виправлено на «" + group.code() + "».");
                }
                readCurator(header.row(), group);
            }
        }
        return headers;
    }

    private boolean ownsCell(int row, int column) {
        CellRangeAddress region = grid.region(row, column);
        return region == null || (region.getFirstRow() == row && region.getFirstColumn() == column);
    }

    private static int majorityCourse(List<HeaderGroup> groups) {
        Map<Integer, Integer> counts = new HashMap<>();
        groups.forEach(group -> counts.merge(group.code().course(), 1, Integer::sum));
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
    }

    private void readCurator(int headerRow, HeaderGroup group) {
        if (headerRow == 0) {
            return;
        }
        String raw = grid.text(headerRow - 1, group.column());
        if (raw.isEmpty() || GroupCode.parse(raw).isPresent() || TextNormalizer.key(raw).contains("курс")) {
            return;
        }
        String location = grid.location(headerRow - 1, group.column());
        Matcher withActing = CURATOR_WITH_ACTING.matcher(raw);
        String main = withActing.matches() ? withActing.group(1) : raw;
        PersonName curator = PersonName.parse(main);
        if (curator == null) {
            return;
        }
        curators.add(new CuratorEntry(curator, main, group.code().code(), false, location));
        if (withActing.matches()) {
            PersonName acting = PersonName.parse(withActing.group(2));
            if (acting != null) {
                curators.add(new CuratorEntry(acting, withActing.group(2), group.code().code(), true, location));
                issues.warn(RowType.TEACHER, raw, location, "Для групи «" + group.code() + "» вказано двох кураторів: «"
                        + curator.shortForm() + "» і в дужках «" + acting.shortForm()
                        + "». Куратором записано першого — перевірте.");
            }
        }
    }

    private String findTitle(int beforeRow) {
        for (int row = 0; row < beforeRow; row++) {
            for (int column = 0; column <= grid.lastColumn(); column++) {
                String text = grid.text(row, column);
                if (TextNormalizer.key(text).replace(" ", "").contains("розкладзанять")) {
                    return text;
                }
            }
        }
        return null;
    }

    private String findSemesterLabel(int beforeRow) {
        for (int row = 0; row < beforeRow; row++) {
            for (int column = 0; column <= grid.lastColumn(); column++) {
                String text = grid.text(row, column);
                if (TextNormalizer.key(text).contains("семестр")) {
                    return text;
                }
            }
        }
        return null;
    }

    // ───────────────────────── дні та пари ─────────────────────────

    private record Day(int weekday, int firstRow, int lastRow) {
    }

    private record PairRows(int number, int top, int bottom) {

        boolean twoRows() {
            return bottom >= 0;
        }
    }

    private void parseBlock(Header header, List<HeaderGroup> blockGroups, int firstRow, int lastRow) {
        List<Day> days = findDays(firstRow, lastRow);
        if (days.isEmpty()) {
            issues.error(RowType.FILE, null, grid.location(header.row(), 0),
                    "Для груп " + header.course() + " курсу не знайдено днів тижня в колонці A.");
            return;
        }
        Set<Integer> weekdays = new HashSet<>();
        for (Day day : days) {
            if (!weekdays.add(day.weekday())) {
                issues.warn(RowType.CELL, grid.text(day.firstRow(), 0), grid.location(day.firstRow(), 0),
                        "День тижня повторюється в блоці " + header.course() + " курсу.");
            }
            for (PairRows pair : findPairs(day)) {
                for (HeaderGroup group : blockGroups) {
                    parseSlot(group, day, pair);
                }
            }
        }
    }

    private List<Day> findDays(int firstRow, int lastRow) {
        List<Day> found = new ArrayList<>();
        List<Boolean> merged = new ArrayList<>();
        for (int row = firstRow; row <= lastRow; row++) {
            if (!grid.startsAt(row, 0)) {
                continue;
            }
            Integer weekday = WEEKDAYS.get(TextNormalizer.key(grid.text(row, 0)).replace(" ", ""));
            if (weekday == null) {
                continue;
            }
            CellRangeAddress region = grid.region(row, 0);
            found.add(new Day(weekday, row, region == null ? -1 : Math.min(region.getLastRow(), lastRow)));
            merged.add(region != null);
        }
        List<Day> days = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            Day day = found.get(i);
            if (merged.get(i)) {
                days.add(day);
            } else {
                int end = i + 1 < found.size() ? found.get(i + 1).firstRow() - 1 : lastRow;
                days.add(new Day(day.weekday(), day.firstRow(), end));
            }
        }
        return days;
    }

    private List<PairRows> findPairs(Day day) {
        List<PairRows> pairs = new ArrayList<>();
        Set<Integer> numbers = new HashSet<>();
        for (int row = day.firstRow(); row <= day.lastRow(); row++) {
            if (!grid.startsAt(row, 1)) {
                continue;
            }
            Integer number = pairNumber(grid.text(row, 1));
            if (number == null) {
                continue;
            }
            CellRangeAddress region = grid.region(row, 1);
            int last;
            if (region != null) {
                last = Math.min(region.getLastRow(), day.lastRow());
            } else if (row + 1 <= day.lastRow() && grid.startsAt(row + 1, 1) && pairNumber(grid.text(row + 1, 1)) == null) {
                last = row + 1;
            } else {
                last = row;
            }
            if (last - row > 1) {
                issues.warn(RowType.CELL, grid.text(row, 1), grid.location(row, 1),
                        "Пара займає більше двох рядків — враховано лише перші два.");
                last = row + 1;
            }
            if (!numbers.add(number)) {
                issues.warn(RowType.CELL, grid.text(row, 1), grid.location(row, 1),
                        "Номер пари " + number + " повторюється в межах дня.");
            }
            pairs.add(new PairRows(number, row, last == row ? -1 : last));
            row = last;
        }
        return pairs;
    }

    private static Integer pairNumber(String text) {
        Matcher matcher = PAIR_NUMBER.matcher(text);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : null;
    }

    // ───────────────────────── клітинки групи ─────────────────────────

    /** Discipline, teacher and room texts of one row (or of the bottom row without merged parts). */
    private record Cells(String discipline, String teacher, String room) {

        boolean isEmpty() {
            return discipline.isEmpty() && teacher.isEmpty() && room.isEmpty();
        }

        Cells withRoomIfMissing(String fallback) {
            return room.isEmpty() ? new Cells(discipline, teacher, fallback) : this;
        }
    }

    private void parseSlot(HeaderGroup group, Day day, PairRows pair) {
        int column = group.column();
        int top = pair.top();
        Cells upper = new Cells(grid.text(top, column), grid.text(top, column + 1), grid.text(top, column + 2));
        Slot slot = new Slot(group, day, pair, grid.location(top, column));
        if (!pair.twoRows()) {
            if (!upper.isEmpty()) {
                slot.emit(Parity.BOTH, 0, upper);
            }
            return;
        }
        int bottom = pair.bottom();
        Cells lower = new Cells(own(top, bottom, column), own(top, bottom, column + 1), own(top, bottom, column + 2));
        if (upper.isEmpty() && lower.isEmpty()) {
            return;
        }
        if (grid.spansRows(top, bottom, column)) {
            everyWeek(slot, upper, lower);
        } else {
            separateRows(slot, upper, lower);
        }
    }

    /** Bottom-row text unless the cell is covered by a region that starts at or above the top row. */
    private String own(int top, int bottom, int column) {
        CellRangeAddress region = grid.region(bottom, column);
        if (region != null && region.getFirstRow() <= top) {
            return "";
        }
        return grid.text(bottom, column);
    }

    /** The discipline cell is merged over both rows: the lesson happens every week. */
    private void everyWeek(Slot slot, Cells upper, Cells lower) {
        String discipline = upper.discipline();
        boolean bothTeachers = !upper.teacher().isEmpty() && !lower.teacher().isEmpty();
        if (bothTeachers && !sameText(upper.teacher(), lower.teacher())) {
            slot.emit(Parity.BOTH, 1, upper);
            slot.emit(Parity.BOTH, 2, new Cells(discipline, lower.teacher(), lower.room()).withRoomIfMissing(upper.room()));
            return;
        }
        String teacher = upper.teacher().isEmpty() ? lower.teacher() : upper.teacher();
        if (upper.teacher().isEmpty() && !lower.teacher().isEmpty()) {
            issues.info(RowType.CELL, lower.teacher(), slot.location(),
                    "Викладача вказано лише в нижньому рядку пари — вважаю заняття щотижневим.");
        }
        if (!upper.room().isEmpty() && !lower.room().isEmpty() && !sameText(upper.room(), lower.room())) {
            issues.info(RowType.CELL, upper.room() + " / " + lower.room(), slot.location(),
                    "Одна дисципліна й викладач, але різні аудиторії по рядках — чисельник і знаменник в різних аудиторіях.");
            slot.emit(Parity.NUMERATOR, 0, new Cells(discipline, teacher, upper.room()));
            slot.emit(Parity.DENOMINATOR, 0, new Cells(discipline, teacher, lower.room()));
            return;
        }
        String room = upper.room().isEmpty() ? lower.room() : upper.room();
        slot.emit(Parity.BOTH, 0, new Cells(discipline, teacher, room));
    }

    /** Separate cells per row: top = numerator, bottom = denominator (with the dirty-data cases). */
    private void separateRows(Slot slot, Cells upper, Cells lower) {
        if (lower.isEmpty()) {
            slot.emit(Parity.NUMERATOR, 0, upper);
            return;
        }
        if (upper.isEmpty()) {
            slot.emit(Parity.DENOMINATOR, 0, lower);
            return;
        }
        boolean upperHasDiscipline = !upper.discipline().isEmpty();
        if (upperHasDiscipline != !lower.discipline().isEmpty()) {
            Cells withDiscipline = upperHasDiscipline ? upper : lower;
            Cells other = upperHasDiscipline ? lower : upper;
            if (withDiscipline.teacher().isEmpty() || other.teacher().isEmpty()) {
                issues.warn(RowType.CELL, other.room().isEmpty() ? other.teacher() : other.room(), slot.location(),
                        "У другому рядку пари немає дисципліни — його вміст пропущено.");
                slot.emit(upperHasDiscipline ? Parity.NUMERATOR : Parity.DENOMINATOR, 0, withDiscipline);
                return;
            }
            String discipline = withDiscipline.discipline();
            issues.warn(RowType.CELL, discipline, slot.location(),
                    "Дисципліну записано один раз, а викладачів — у двох рядках без об'єднання клітинок: вважаю це підгрупами 1 і 2.");
            slot.emit(Parity.BOTH, 1, new Cells(discipline, upper.teacher(), upper.room()));
            slot.emit(Parity.BOTH, 2, new Cells(discipline, lower.teacher(), lower.room()).withRoomIfMissing(upper.room()));
            return;
        }
        if (TextNormalizer.abbreviationKey(upper.discipline()).equals(TextNormalizer.abbreviationKey(lower.discipline()))) {
            if (sameText(upper.teacher(), lower.teacher())) {
                if (sameText(upper.room(), lower.room()) || lower.room().isEmpty()) {
                    issues.info(RowType.CELL, upper.discipline(), slot.location(),
                            "Однакове заняття в обох рядках без об'єднання клітинок — вважаю щотижневим.");
                    slot.emit(Parity.BOTH, 0, upper);
                    return;
                }
            } else {
                issues.warn(RowType.CELL, upper.discipline(), slot.location(),
                        "Та сама дисципліна з різними викладачами в окремих рядках: імпортовано як чисельник і знаменник. "
                                + "Якщо це підгрупи — виправте в редакторі розкладу.");
            }
        }
        slot.emit(Parity.NUMERATOR, 0, upper);
        slot.emit(Parity.DENOMINATOR, 0, lower);
    }

    private static boolean sameText(String a, String b) {
        return TextNormalizer.key(a).equals(TextNormalizer.key(b));
    }

    /** Emits lessons of one group, day and pair; handles «/», markers and embedded surnames. */
    private final class Slot {

        private final HeaderGroup group;
        private final Day day;
        private final PairRows pair;
        private final String location;

        Slot(HeaderGroup group, Day day, PairRows pair, String location) {
            this.group = group;
            this.day = day;
            this.pair = pair;
            this.location = location;
        }

        String location() {
            return location;
        }

        void emit(Parity parity, int subgroup, Cells cells) {
            String discipline = cells.discipline();
            String teacher = cells.teacher();
            if (discipline.isEmpty()) {
                if (!teacher.isEmpty()) {
                    issues.warn(RowType.CELL, teacher, location, "Викладача вказано без дисципліни — заняття пропущено.");
                } else if (!cells.room().isEmpty()) {
                    issues.warn(RowType.CELL, cells.room(), location,
                            "Аудиторію вказано без дисципліни і викладача — заняття пропущено.");
                }
                return;
            }
            if (teacher.isEmpty()) {
                Matcher embedded = EMBEDDED_TEACHER.matcher(discipline);
                if (embedded.matches()) {
                    issues.warn(RowType.CELL, discipline, location, "Дисципліну й викладача записано в одній клітинці — розділено на «"
                            + embedded.group(1) + "» і «" + embedded.group(2) + "».");
                    discipline = embedded.group(1);
                    teacher = embedded.group(2);
                }
            }
            List<String> teachers = splitSlash(teacher);
            List<String> rooms = splitSlash(cells.room());
            if (teachers.size() > 1) {
                if (subgroup != 0) {
                    issues.warn(RowType.CELL, teacher, location,
                            "Кілька викладачів через «/» у рядку підгрупи — враховано лише першого.");
                    add(parity, subgroup, discipline, teachers.getFirst(), rooms.isEmpty() ? "" : rooms.getFirst());
                    return;
                }
                if (rooms.size() > 1 && rooms.size() != teachers.size()) {
                    issues.warn(RowType.CELL, cells.room(), location,
                            "Кількість аудиторій не збігається з кількістю викладачів підгруп.");
                }
                for (int i = 0; i < teachers.size(); i++) {
                    String room = rooms.size() == teachers.size() ? rooms.get(i) : rooms.size() == 1 ? rooms.getFirst() : "";
                    add(parity, i + 1, discipline, teachers.get(i), room);
                }
                return;
            }
            if (rooms.size() > 1) {
                issues.warn(RowType.CELL, cells.room(), location,
                        "Кілька аудиторій через «/» для одного викладача — враховано першу.");
            }
            add(parity, subgroup, discipline, teachers.isEmpty() ? "" : teachers.getFirst(),
                    rooms.isEmpty() ? "" : rooms.getFirst());
        }

        private void add(Parity parity, int subgroup, String discipline, String teacherText, String room) {
            PersonName teacher = PersonName.parse(teacherText);
            int effectiveSubgroup = subgroup;
            if (teacher != null && teacher.subgroupMarker() && subgroup == 0) {
                effectiveSubgroup = 1;
                issues.warn(RowType.LESSON, teacherText, location, "Позначка «(п)»: заняття лише для однієї підгрупи, "
                        + "номер підгрупи в розкладі не вказано — призначено підгрупу 1.");
            }
            if (teacher == null) {
                issues.warn(RowType.LESSON, discipline, location, "Не вказано викладача — заняття імпортується без викладача.");
            }
            String cleanDiscipline = stripTeacherSurname(discipline, teacher);
            lessons.add(new RawLesson(day.weekday(), pair.number(), parity, group.code().code(), effectiveSubgroup,
                    cleanDiscipline, teacher, TextNormalizer.clean(teacherText), TextNormalizer.clean(room), location));
        }

        /** «Матем.Овчар Ін.» with teacher «Овчар Ін.» → «Матем.». */
        private String stripTeacherSurname(String discipline, PersonName teacher) {
            if (teacher == null || teacher.surname().length() < 4) {
                return discipline;
            }
            String lower = discipline.toLowerCase(TextNormalizer.UK);
            int index = lower.lastIndexOf(teacher.surname().toLowerCase(TextNormalizer.UK));
            if (index <= 0) {
                return discipline;
            }
            String stripped = TextNormalizer.clean(discipline.substring(0, index));
            issues.info(RowType.CELL, discipline, location,
                    "З клітинки дисципліни прибрано прізвище викладача: «" + stripped + "».");
            return stripped;
        }
    }

    private static List<String> splitSlash(String text) {
        List<String> parts = new ArrayList<>();
        for (String part : text.split("/")) {
            String clean = TextNormalizer.clean(part);
            if (!clean.isEmpty()) {
                parts.add(clean);
            }
        }
        return parts;
    }
}
