package ua.vtc.rozklad.importer.calendar;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import ua.vtc.rozklad.importer.apply.CatalogKeys;
import ua.vtc.rozklad.importer.apply.CoreImportPayloads;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot.PeriodKindItem;
import ua.vtc.rozklad.importer.job.JobOptions;
import ua.vtc.rozklad.importer.normalize.EntityType;
import ua.vtc.rozklad.importer.normalize.NormalizationEngine.NormalizationResult;
import ua.vtc.rozklad.importer.normalize.Resolution;
import ua.vtc.rozklad.importer.report.ReportRow;
import ua.vtc.rozklad.importer.report.RowType;
import ua.vtc.rozklad.importer.report.Severity;

/**
 * What the calendar sends to core, built from the file's runs with the catalog and the options (pure, so the
 * report and apply build the same request): codes → period kinds by code or alias; one period per run, runs with
 * the same kind, title, dates and room merged into one period of their groups; scope ALL when the groups are every
 * active catalog group, COURSES when they are exactly the active groups of some courses, else GROUPS; the order
 * note from the kind's template; {@code sourceKey} = year|kind code|start|end|8 hex of SHA-256 of the title.
 */
final class CalendarPlan {

    static final int MAX_PERIODS = 3000;
    static final int MAX_GROUP_SEMESTERS = 1000;
    private static final int LISTED_GROUPS = 8;

    private CalendarPlan() {
    }

    record Built(List<AcademicCalendarDraft.Code> codes, List<AcademicCalendarDraft.Period> periods,
                 List<CoreImportPayloads.GroupSemester> groupSemesters, List<ReportRow> rows) {

        boolean blocking() {
            return rows.stream().anyMatch(ReportRow::blocking);
        }

        CoreImportPayloads.CalendarPeriodsRequest request(String academicYear) {
            return new CoreImportPayloads.CalendarPeriodsRequest(academicYear,
                    periods.stream().map(AcademicCalendarDraft.Period::payload).toList(), groupSemesters);
        }
    }

    /** A merged period while it is being built. */
    private static final class Item {
        final String kindCode;
        final PeriodKindItem kind;
        final String title;
        final AcademicCalendarDraft.Run first;
        final String roomName;
        final List<String> groups = new ArrayList<>();
        final List<String> cells = new ArrayList<>();
        boolean unresolvedGroup;

        Item(PeriodKindItem kind, String title, AcademicCalendarDraft.Run first, String roomName) {
            this.kindCode = kind.code();
            this.kind = kind;
            this.title = title;
            this.first = first;
            this.roomName = roomName;
        }
    }

    static Built build(AcademicCalendarDraft draft, JobOptions options, CatalogSnapshot catalog,
                       NormalizationResult normalization) {
        List<ReportRow> rows = new ArrayList<>();
        CatalogKeys keys = new CatalogKeys(normalization, catalog);
        Map<String, PeriodKindItem> kinds = kindsByCode(catalog);
        Set<String> skipCodes = new HashSet<>(options.skipCodes());
        Set<String> skipGroups = new HashSet<>(options.skipGroups());
        if (catalog.periodKinds().isEmpty()) {
            rows.add(ReportRow.of(Severity.ERROR, RowType.PERIOD_KIND, null, null, "Сервер розкладу не передав види "
                    + "періодів: оновіть сервер розкладу, потім збережіть параметри імпорту ще раз."));
        }
        Map<String, Integer> groupOrder = new HashMap<>();
        Map<String, String> catalogCodes = new HashMap<>();
        Set<String> unresolved = new HashSet<>();
        for (AcademicCalendarDraft.Group group : draft.groups()) {
            groupOrder.put(group.code(), groupOrder.size());
            Resolution resolution = keys.resolution(EntityType.GROUP, group.code()).orElse(null);
            if (resolution != null && resolution.existing()) {
                catalogCodes.put(group.code(), keys.key(EntityType.GROUP, group.code(), "code"));
            } else {
                catalogCodes.put(group.code(), group.code());
                unresolved.add(group.code());
            }
        }

        // коди → види
        List<AcademicCalendarDraft.Code> codes = new ArrayList<>();
        Set<String> usable = new HashSet<>();
        for (AcademicCalendarDraft.Code code : draft.codes()) {
            PeriodKindItem kind = kinds.get(code.code());
            codes.add(new AcademicCalendarDraft.Code(code.code(), kind == null ? null : kind.code(),
                    kind == null ? null : kind.name(), kind == null ? null : kind.effect(), code.cells()));
            List<AcademicCalendarDraft.Run> runs = draft.runs().stream()
                    .filter(run -> run.code().equals(code.code()) && !skipGroups.contains(run.groupCode())).toList();
            if (runs.isEmpty()) {
                continue;
            }
            String location = runs.getFirst().cells();
            if (skipCodes.contains(code.code()) || kind != null && skipCodes.contains(JobOptions.periodCode(kind.code()))) {
                rows.add(ReportRow.of(Severity.INFO, RowType.PERIOD_KIND, code.code(), location, "Код «" + code.code()
                        + "» пропущено (параметри імпорту): " + runs.size() + " " + plural(runs.size(), "період", "періоди",
                        "періодів") + " не імпортуються."));
            } else if (kind == null) {
                if (!catalog.periodKinds().isEmpty()) {
                    rows.add(ReportRow.of(Severity.ERROR, RowType.PERIOD_KIND, code.code(), location, "Невідомий код «"
                            + code.code() + "» у " + groupsText(runs) + ": додайте вид у «Видах періодів» або пропустіть код."));
                }
            } else if (!kind.isActive()) {
                rows.add(ReportRow.of(Severity.ERROR, RowType.PERIOD_KIND, code.code(), location, "Вид «" + kind.code()
                        + "» вимкнено: увімкніть його або пропустіть код."));
            } else if ("ONLINE".equals(kind.effect())) {
                // графік не каже, онлайн чи змішано, а core без режиму такий період не прийме
                rows.add(ReportRow.of(Severity.ERROR, RowType.PERIOD_KIND, code.code(), location, "Вид «" + kind.code()
                        + "» з ефектом «онлайн» не імпортується з графіка (у графіку немає режиму навчання): пропустіть "
                        + "код і додайте такий період у календарі вручну."));
            } else {
                usable.add(code.code());
            }
        }

        // пробіги → періоди
        Map<String, Item> items = new LinkedHashMap<>();
        Set<String> roomlessKinds = new TreeSet<>();
        for (AcademicCalendarDraft.Run run : draft.runs()) {
            if (!usable.contains(run.code()) || skipGroups.contains(run.groupCode())) {
                continue;
            }
            PeriodKindItem kind = kinds.get(run.code());
            String kindCode = JobOptions.periodCode(kind.code());
            String title = firstNonBlank(run.title(), options.effectiveTitles().get(kindCode),
                    options.effectiveTitles().get(run.code()), kind.name());
            String room = firstNonBlank(options.effectiveRooms().get(kindCode), options.effectiveRooms().get(run.code()));
            if (room != null && !"SUSPEND".equals(kind.effect())) {
                roomlessKinds.add(kind.code());
                room = null;
            }
            String roomName = room == null ? null : keys.key(EntityType.ROOM, room, "name");
            String key = kindCode + "|" + title + "|" + run.startsOn() + "|" + run.endsOn() + "|" + roomName;
            Item item = items.computeIfAbsent(key, any -> new Item(kind, title, run, roomName));
            item.groups.add(catalogCodes.getOrDefault(run.groupCode(), run.groupCode()));
            item.cells.add(run.cells());
            item.unresolvedGroup |= unresolved.contains(run.groupCode());
        }
        for (String kind : roomlessKinds) {
            rows.add(ReportRow.of(Severity.INFO, RowType.PERIOD_KIND, kind, null, "Аудиторію для «" + kind + "» не "
                    + "передано: її вказують лише для виду, коли група не на парах."));
        }
        List<Item> ordered = new ArrayList<>(items.values());
        ordered.sort(Comparator.comparing((Item item) -> item.first.startsOn()).thenComparing(item -> item.kindCode)
                .thenComparingInt(item -> groupOrder.getOrDefault(item.first.groupCode(), 0)));
        Set<String> sourceKeys = new HashSet<>();
        List<AcademicCalendarDraft.Period> periods = new ArrayList<>();
        for (Item item : ordered) {
            String scope = item.unresolvedGroup ? "GROUPS" : scope(item.groups, catalog);
            List<Integer> courses = "COURSES".equals(scope) ? courses(item.groups, catalog) : null;
            List<String> groupCodes = "GROUPS".equals(scope) ? List.copyOf(item.groups) : List.of();
            String note = NoteTemplate.render(item.kind.noteTemplate(), new NoteTemplate.Values(
                    NoteTemplate.audience(scope, item.groups, courses), item.title, item.kind.name(), item.first.startsOn(),
                    item.first.endsOn(), item.roomName, null));
            String sourceKey = draft.academicYear() + "|" + item.kindCode + "|" + item.first.startsOn() + "|"
                    + item.first.endsOn() + "|" + sha8(item.title);
            String unique = sourceKey;
            for (int n = 2; !sourceKeys.add(unique); n++) {
                unique = sourceKey + "|" + n;
            }
            periods.add(new AcademicCalendarDraft.Period(unique, item.kindCode, item.title, item.first.startsOn(),
                    item.first.endsOn(), scope, groupCodes, courses, item.roomName, null, note, cells(item.cells)));
        }

        List<CoreImportPayloads.GroupSemester> groupSemesters = new ArrayList<>();
        for (AcademicCalendarDraft.Group group : draft.groups()) {
            if (skipGroups.contains(group.code()) || group.theoryWeeks().isEmpty() && group.exams().isEmpty()) {
                continue;
            }
            for (int semester = 1; semester <= 2; semester++) {
                groupSemesters.add(new CoreImportPayloads.GroupSemester(catalogCodes.get(group.code()), semester,
                        at(group.theoryWeeks(), semester - 1), at(group.exams(), semester - 1)));
            }
        }
        if (periods.isEmpty() && !rows.stream().anyMatch(ReportRow::blocking)) {
            rows.add(ReportRow.of(Severity.ERROR, RowType.PERIOD, null, null, "У графіку не лишилося жодного періоду для "
                    + "імпорту: перевірте пропущені коди й групи в параметрах імпорту."));
        }
        if (periods.size() > MAX_PERIODS || groupSemesters.size() > MAX_GROUP_SEMESTERS) {
            rows.add(ReportRow.of(Severity.ERROR, RowType.PERIOD, null, null, "Графік завеликий для одного імпорту: періодів "
                    + periods.size() + " (не більше " + MAX_PERIODS + "), записів тижнів груп " + groupSemesters.size()
                    + " (не більше " + MAX_GROUP_SEMESTERS + ")."));
        }
        return new Built(codes, periods, groupSemesters, rows);
    }

    /** Kinds by folded code and aliases; a code shared by two kinds keeps the first (core forbids it anyway). */
    static Map<String, PeriodKindItem> kindsByCode(CatalogSnapshot catalog) {
        Map<String, PeriodKindItem> kinds = new HashMap<>();
        for (PeriodKindItem kind : catalog.periodKinds()) {
            kinds.putIfAbsent(JobOptions.periodCode(kind.code()), kind);
        }
        for (PeriodKindItem kind : catalog.periodKinds()) {
            kind.aliases().forEach(alias -> kinds.putIfAbsent(JobOptions.periodCode(alias), kind));
        }
        return kinds;
    }

    private static String scope(List<String> groups, CatalogSnapshot catalog) {
        Set<String> chosen = new HashSet<>(groups);
        Set<String> active = new HashSet<>();
        catalog.groups().stream().filter(CatalogSnapshot.GroupItem::isActive).forEach(group -> active.add(group.code()));
        if (!active.isEmpty() && chosen.equals(active)) {
            return "ALL";
        }
        Map<String, Integer> courseOf = new HashMap<>();
        catalog.groups().forEach(group -> courseOf.put(group.code(), group.course()));
        Set<Integer> courses = new HashSet<>();
        for (String group : chosen) {
            courses.add(courseOf.get(group));
        }
        if (courses.contains(null)) {
            return "GROUPS";
        }
        Set<String> ofCourses = new HashSet<>();
        catalog.groups().stream().filter(CatalogSnapshot.GroupItem::isActive)
                .filter(group -> courses.contains(group.course())).forEach(group -> ofCourses.add(group.code()));
        return chosen.equals(ofCourses) && chosen.size() > 1 ? "COURSES" : "GROUPS";
    }

    private static List<Integer> courses(List<String> groups, CatalogSnapshot catalog) {
        Set<Integer> courses = new TreeSet<>();
        catalog.groups().stream().filter(group -> groups.contains(group.code())).forEach(group -> courses.add(group.course()));
        return List.copyOf(courses);
    }

    static String sha8(String title) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(title.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", hash[i] & 0xFF));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String cells(List<String> cells) {
        if (cells.size() <= 3) {
            return String.join(", ", cells);
        }
        return String.join(", ", cells.subList(0, 3)) + " … (ще " + (cells.size() - 3) + ")";
    }

    /** «групи 4КІ1» / «груп 4КІ1, 4КІ2» (at most {@value #LISTED_GROUPS} codes, then «і ще N»). */
    private static String groupsText(List<AcademicCalendarDraft.Run> runs) {
        Set<String> groups = new LinkedHashSet<>();
        runs.forEach(run -> groups.add(run.groupCode()));
        List<String> list = new ArrayList<>(groups);
        String text = String.join(", ", list.subList(0, Math.min(LISTED_GROUPS, list.size())));
        if (list.size() > LISTED_GROUPS) {
            text += " і ще " + (list.size() - LISTED_GROUPS);
        }
        return (list.size() == 1 ? "групи " : "груп ") + text;
    }

    static String plural(int n, String one, String few, String many) {
        int mod100 = n % 100;
        int mod10 = n % 10;
        if (mod10 == 1 && mod100 != 11) {
            return one;
        }
        return mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14) ? few : many;
    }

    private static Integer at(List<Integer> values, int index) {
        return index < values.size() ? values.get(index) : null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
