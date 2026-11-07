package ua.vtc.rozklad.importer.timetable;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import ua.vtc.rozklad.importer.report.Issues;
import ua.vtc.rozklad.importer.report.RowType;
import ua.vtc.rozklad.importer.text.TextNormalizer;
import ua.vtc.rozklad.importer.timetable.TimetableDraft.BellProfile;
import ua.vtc.rozklad.importer.timetable.TimetableDraft.BellSlot;

/**
 * Bell sheets («дзвінки (2 зм)», «дзвінки»): tables of «pair number | HH:MM-HH:MM» anywhere on the
 * sheet, each optionally titled «ПОНЕДІЛОК-ЧЕТВЕР» or «П'ЯТНИЦЯ» (no title = every weekday). Tables
 * with the same pair length form one profile named like DOMAIN §2: «2 зміни, 60 хв».
 */
final class BellSheetParser {

    private static final Pattern PAIR = Pattern.compile("^([1-8])\\s*\\.?$");
    private static final Pattern TIME_RANGE =
            Pattern.compile("^(\\d{1,2})[:.](\\d{2})\\s*[-–—]\\s*(\\d{1,2})[:.](\\d{2})$");
    private static final int TITLE_SEARCH_ROWS = 5;

    private final Issues issues;

    BellSheetParser(Issues issues) {
        this.issues = issues;
    }

    private record Table(List<Integer> weekdays, List<BellSlot> pairs, String location) {

        int pairMinutes() {
            BellSlot first = pairs.getFirst();
            return (int) Duration.between(first.startsAt(), first.endsAt()).toMinutes();
        }
    }

    List<BellProfile> parse(Workbook workbook) {
        List<Table> tables = new ArrayList<>();
        for (Sheet sheet : workbook) {
            if (TextNormalizer.key(sheet.getSheetName()).contains("дзвінк")) {
                tables.addAll(tables(new SheetGrid(sheet)));
            }
        }
        Map<Integer, Map<Integer, List<BellSlot>>> byLength = new TreeMap<>((a, b) -> Integer.compare(b, a));
        Map<Integer, String> firstLocation = new LinkedHashMap<>();
        for (Table table : tables) {
            Map<Integer, List<BellSlot>> profile = byLength.computeIfAbsent(table.pairMinutes(), key -> new TreeMap<>());
            firstLocation.putIfAbsent(table.pairMinutes(), table.location());
            for (int weekday : table.weekdays()) {
                List<BellSlot> slots = table.pairs().stream()
                        .map(slot -> new BellSlot(weekday, slot.pair(), slot.startsAt(), slot.endsAt())).toList();
                List<BellSlot> existing = profile.putIfAbsent(weekday, slots);
                if (existing != null && !existing.equals(slots)) {
                    issues.warn(RowType.BELLS, null, table.location(), "Для одного дня є дві різні таблиці дзвінків з парами по "
                            + table.pairMinutes() + " хв — враховано першу.");
                }
            }
        }
        List<BellProfile> profiles = new ArrayList<>();
        byLength.forEach((minutes, days) -> {
            int maxPair = days.values().stream().flatMap(List::stream).mapToInt(BellSlot::pair).max().orElse(0);
            String name = (maxPair > 4 ? "2 зміни" : "1 зміна") + ", " + minutes + " хв";
            List<BellSlot> slots = days.values().stream().flatMap(List::stream).toList();
            profiles.add(new BellProfile(name, slots));
            validate(name, days, firstLocation.get(minutes));
        });
        return profiles;
    }

    private List<Table> tables(SheetGrid grid) {
        List<Table> tables = new ArrayList<>();
        for (int column = 0; column < grid.lastColumn(); column++) {
            List<BellSlot> current = new ArrayList<>();
            int firstRow = -1;
            int lastRow = -1;
            for (int row = 0; row <= grid.lastRow(); row++) {
                if (!grid.startsAt(row, column)) {
                    continue;
                }
                Matcher pair = PAIR.matcher(grid.text(row, column));
                Matcher time = TIME_RANGE.matcher(grid.text(row, column + 1));
                if (!pair.matches() || !time.matches()) {
                    continue;
                }
                int number = Integer.parseInt(pair.group(1));
                boolean restart = !current.isEmpty() && (number <= current.getLast().pair() || row - lastRow > 4);
                if (restart) {
                    tables.add(table(grid, column, firstRow, current));
                    current = new ArrayList<>();
                }
                if (current.isEmpty()) {
                    firstRow = row;
                }
                LocalTime start = LocalTime.of(Integer.parseInt(time.group(1)), Integer.parseInt(time.group(2)));
                LocalTime end = LocalTime.of(Integer.parseInt(time.group(3)), Integer.parseInt(time.group(4)));
                current.add(new BellSlot(0, number, start, end));
                lastRow = row;
            }
            if (!current.isEmpty()) {
                tables.add(table(grid, column, firstRow, current));
            }
        }
        return tables;
    }

    private static Table table(SheetGrid grid, int column, int firstRow, List<BellSlot> pairs) {
        List<Integer> weekdays = List.of(1, 2, 3, 4, 5);
        for (int row = firstRow - 1; row >= Math.max(0, firstRow - TITLE_SEARCH_ROWS); row--) {
            String title = TextNormalizer.key(grid.text(row, column) + " " + grid.text(row, column + 1));
            if (title.contains("понеділок") && title.contains("четвер")) {
                weekdays = List.of(1, 2, 3, 4);
                break;
            }
            if (title.contains("п'ятниц") || title.contains("пятниц")) {
                weekdays = List.of(5);
                break;
            }
        }
        return new Table(weekdays, List.copyOf(pairs), grid.location(firstRow, column));
    }

    private void validate(String name, Map<Integer, List<BellSlot>> days, String location) {
        days.forEach((weekday, slots) -> {
            for (int i = 1; i < slots.size(); i++) {
                BellSlot previous = slots.get(i - 1);
                BellSlot slot = slots.get(i);
                if (slot.startsAt().isBefore(previous.endsAt()) || slot.pair() != previous.pair() + 1) {
                    issues.warn(RowType.BELLS, name, location, "Профіль дзвінків «" + name
                            + "»: пари йдуть не по черзі або перекриваються (день " + weekday + ").");
                    return;
                }
            }
        });
    }
}
