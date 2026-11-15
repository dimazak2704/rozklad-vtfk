package ua.vtc.rozklad.importer.calendar;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.importer.apply.ApplyException;
import ua.vtc.rozklad.importer.apply.CoreImportClient;
import ua.vtc.rozklad.importer.apply.CoreImportException;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot;
import ua.vtc.rozklad.importer.job.FileType;
import ua.vtc.rozklad.importer.job.ImportHandler;
import ua.vtc.rozklad.importer.job.ImportKind;
import ua.vtc.rozklad.importer.job.JobOptions;
import ua.vtc.rozklad.importer.normalize.EntityCandidate;
import ua.vtc.rozklad.importer.normalize.EntityType;
import ua.vtc.rozklad.importer.normalize.NormalizationEngine.NormalizationResult;
import ua.vtc.rozklad.importer.report.ReportRow;
import ua.vtc.rozklad.importer.report.RowType;
import ua.vtc.rozklad.importer.report.Severity;
import ua.vtc.rozklad.importer.web.ProblemException;

/**
 * ACADEMIC_CALENDAR_XLSX: the college's «робочий графік освітнього процесу» becomes calendar periods of core.
 * Parsing is deterministic ({@link AcademicCalendarParser}); groups (and the rooms of the options) are matched
 * against the catalog and never created; every report rebuild maps the codes to core's period kinds, builds the
 * request ({@link CalendarPlan}) and asks core for a dry-run, whose findings (live-change conflicts, parts outside
 * the system's semesters, periods edited by hand) become report rows. Apply is one step {@code calendar}: the same
 * request without dry-run; core upserts by source key, so sending it again changes nothing.
 */
@Component
public class AcademicCalendarImportHandler implements ImportHandler {

    static final String STEP_CALENDAR = "calendar";
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("dd.MM");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final Pattern SEMESTER_SUFFIX = Pattern.compile("\\|S\\d$");
    private static final Map<String, String> FIELDS = Map.ofEntries(
            Map.entry("kind", "вид"), Map.entry("kindId", "вид"), Map.entry("kindCode", "вид"),
            Map.entry("title", "назва"), Map.entry("startsOn", "початок"), Map.entry("endsOn", "кінець"),
            Map.entry("scope", "охоплення"), Map.entry("courses", "курси"), Map.entry("groups", "групи"),
            Map.entry("groupIds", "групи"), Map.entry("groupCodes", "групи"), Map.entry("room", "аудиторія"),
            Map.entry("roomId", "аудиторія"), Map.entry("roomName", "аудиторія"), Map.entry("studyMode", "режим"),
            Map.entry("orderNote", "примітка"));

    private final CoreImportClient core;
    private final JsonMapper jsonMapper;

    public AcademicCalendarImportHandler(CoreImportClient core, JsonMapper jsonMapper) {
        this.core = core;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public ImportKind kind() {
        return ImportKind.ACADEMIC_CALENDAR_XLSX;
    }

    @Override
    public boolean acceptUpload(FileType type, JobOptions options) {
        if (type != FileType.XLSX) {
            throw ProblemException.unsupportedFile("Графік освітнього процесу завантажують як файл Excel (XLSX), а "
                    + "завантажено " + type.label() + ".");
        }
        return false;
    }

    @Override
    public ParseOutcome parse(ParseInput input) {
        AcademicCalendarParser.Result result = AcademicCalendarParser.parse(input.content(), input.options());
        return new ParseOutcome(result.draft(), result.issues());
    }

    /** Groups of the file (without skipped ones) and the rooms of the options for codes the file has. */
    @Override
    public List<EntityCandidate> entities(JsonNode draftJson, JobOptions options) {
        AcademicCalendarDraft draft = draft(draftJson);
        Set<String> skipped = Set.copyOf(options.skipGroups());
        List<EntityCandidate> entities = new ArrayList<>();
        for (AcademicCalendarDraft.Group group : draft.groups()) {
            if (!skipped.contains(group.code())) {
                entities.add(new EntityCandidate(EntityType.GROUP, group.code(), List.of(group.raw()), null,
                        draft.sheet() + "!рядок " + group.row()));
            }
        }
        Set<String> codes = new java.util.HashSet<>();
        draft.codes().forEach(code -> codes.add(code.code()));
        Map<String, EntityCandidate> rooms = new LinkedHashMap<>();
        options.effectiveRooms().forEach((code, room) -> {
            if (codes.contains(code) && !options.skipCodes().contains(code)) {
                rooms.putIfAbsent(EntityCandidate.key(EntityType.ROOM, room),
                        EntityCandidate.of(EntityType.ROOM, room, "параметри імпорту: rooms." + code));
            }
        });
        entities.addAll(rooms.values());
        return entities;
    }

    @Override
    public boolean creates(EntityType type) {
        return false;
    }

    @Override
    public String missingHint(EntityType type) {
        return type == EntityType.GROUP
                ? "Зіставте її з групою каталогу або пропустіть (параметр імпорту skipGroups)."
                : "Зіставте її з аудиторією каталогу або змініть аудиторії періодів (параметр імпорту rooms).";
    }

    @Override
    public String reviewAgainMessage() {
        return "Перевірка на сервері розкладу перед записом знайшла перешкоди — перегляньте звіт і застосуйте ще раз.";
    }

    @Override
    public Review review(JsonNode draftJson, JobOptions options, CatalogSnapshot catalog,
                         NormalizationResult normalization) {
        AcademicCalendarDraft draft = draft(draftJson);
        CalendarPlan.Built built = CalendarPlan.build(draft, options, catalog, normalization);
        List<ReportRow> rows = new ArrayList<>(built.rows());
        boolean unmatched = normalization.resolutions().stream().anyMatch(resolution -> !resolution.existing());
        JsonNode check = null;
        if (built.blocking() || unmatched) {
            rows.add(ReportRow.of(Severity.INFO, RowType.PERIOD, null, null, "Сервер розкладу перевірить періоди, коли "
                    + "помилки вище буде виправлено."));
        } else {
            try {
                check = core.importCalendarPeriods(built.request(draft.academicYear()), true, null);
                if (check == null || !check.isObject()) {
                    check = null;
                    rows.add(unreachable());
                } else {
                    rows.addAll(checkRows(check, built));
                }
            } catch (CoreImportException e) {
                rows.add(dryRunFailed(e));
            }
        }
        AcademicCalendarDraft refreshed = draft.withDerived(built.codes(), built.periods(), built.groupSemesters(), check);
        return new Review(jsonMapper.valueToTree(refreshed), rows);
    }

    @Override
    public JsonNode apply(ApplyInput input) {
        if (input.steps().done(STEP_CALENDAR) != null) {
            return input.steps().all();
        }
        AcademicCalendarDraft draft = draft(input.draft());
        CalendarPlan.Built built = CalendarPlan.build(draft, input.options(), input.catalog(), input.normalization());
        built.rows().stream().filter(ReportRow::blocking).findFirst().ifPresent(row -> {
            throw new ApplyException("CALENDAR_NOT_READY", row.message());
        });
        if (built.periods().isEmpty()) {
            throw new ApplyException("NOTHING_TO_APPLY", "У графіку немає жодного періоду для імпорту.");
        }
        JsonNode result = core.importCalendarPeriods(built.request(draft.academicYear()), false,
                input.steps().idempotencyKey(STEP_CALENDAR));
        input.steps().finished(STEP_CALENDAR, result);
        return input.steps().all();
    }

    /** Core's 409 {@code conflicts[]}: the job returns to review with one row per conflict. */
    @Override
    public List<ReportRow> rejectionRows(CoreImportException rejection) {
        JsonNode problem = rejection.problem();
        if (rejection.status() != 409 || problem == null || !problem.path("conflicts").isArray()) {
            return List.of();
        }
        List<ReportRow> rows = new ArrayList<>();
        problem.path("conflicts").forEach(conflict -> rows.add(conflictRow(conflict)));
        return rows;
    }

    // ───────────────────────── core's dry-run → report ─────────────────────────

    List<ReportRow> checkRows(JsonNode check, CalendarPlan.Built built) {
        Map<String, AcademicCalendarDraft.Period> bySourceKey = new HashMap<>();
        built.periods().forEach(period -> bySourceKey.put(period.sourceKey(), period));
        List<ReportRow> rows = new ArrayList<>();
        check.path("conflicts").forEach(conflict -> rows.add(conflictRow(conflict)));
        if (!check.path("canApply").asBoolean(true) && check.path("conflicts").isEmpty()) {
            rows.add(ReportRow.of(Severity.ERROR, RowType.PERIOD, null, null, "Сервер розкладу не дозволяє застосувати "
                    + "цей імпорт: перевірте періоди графіка."));
        }
        for (JsonNode item : check.path("outOfSemester")) {
            AcademicCalendarDraft.Period period = bySourceKey.get(item.path("sourceKey").asString(""));
            String what = item.path("kindCode").asString("") + " " + audience(item, period) + " "
                    + range(item.path("startsOn"), item.path("endsOn"));
            List<String> kept = new ArrayList<>();
            item.path("kept").forEach(part -> {
                if (part.has("startsOn") && part.has("endsOn")) {
                    kept.add(range(part.path("startsOn"), part.path("endsOn")));
                }
            });
            String tail;
            if (item.path("kept").isEmpty()) {
                tail = "не імпортується";
            } else if (kept.isEmpty()) {
                tail = "імпортується лише частина в семестрах";
            } else {
                tail = "обрізано до " + String.join(", ", kept);
            }
            rows.add(ReportRow.of(Severity.WARN, RowType.PERIOD, item.path("kindCode").asString(null),
                    period == null ? null : period.cells(), what + " поза семестрами системи: " + tail + "."));
        }
        for (JsonNode manual : check.path("manual")) {
            List<String> differs = new ArrayList<>();
            manual.path("differs").forEach(field -> {
                String label = FIELDS.getOrDefault(field.asString(""), field.asString(""));
                if (!differs.contains(label)) {
                    differs.add(label);
                }
            });
            if (differs.isEmpty()) {
                continue;
            }
            AcademicCalendarDraft.Period period = bySourceKey.get(
                    SEMESTER_SUFFIX.matcher(manual.path("storedKey").asString("")).replaceFirst(""));
            rows.add(ReportRow.of(Severity.WARN, RowType.PERIOD, manual.path("title").asString(null),
                    period == null ? null : period.cells(), "Період «" + manual.path("title").asString("") + "» змінено "
                            + "вручну, імпорт його не чіпає (різниця: " + String.join(", ", differs) + ")."));
        }
        for (JsonNode overlap : check.path("manualOverlaps")) {
            rows.add(ReportRow.of(Severity.INFO, RowType.PERIOD, overlap.path("title").asString(null), null,
                    "Ручний період «" + overlap.path("title").asString("") + "» (" + overlap.path("kindCode").asString("")
                            + ", " + audience(overlap, null) + ", " + range(overlap.path("startsOn"), overlap.path("endsOn"))
                            + ") перетинається з періодом графіка: обидва лишаться."));
        }
        for (JsonNode skipped : check.path("groupSemesters").path("skipped")) {
            rows.add(ReportRow.of(Severity.WARN, RowType.PERIOD, skipped.path("groupCode").asString(null), null,
                    "Тижні теорії й іспити групи " + skipped.path("groupCode").asString("") + " за семестр "
                            + skipped.path("semesterNumber").asInt() + " не імпортуються: "
                            + skipped.path("reason").asString("причину не вказано.")));
        }
        rows.add(ReportRow.of(Severity.INFO, RowType.PERIOD, null, null, "Буде створено " + check.path("created").asInt()
                + ", оновлено " + check.path("updated").asInt() + ", видалено " + check.path("deleted").asInt()
                + ", без змін " + check.path("unchanged").asInt() + "."));
        JsonNode lessons = check.path("lessons");
        int added = lessons.path("added").asInt();
        int removed = lessons.path("removed").asInt();
        int updated = lessons.path("updated").asInt();
        if (added + removed + updated > 0) {
            rows.add(ReportRow.of(Severity.INFO, RowType.PERIOD, null, null, "Заняття в розкладі від сьогодні: знято — "
                    + removed + ", повернуто — " + added + ", змінено — " + updated + "."));
        }
        return rows;
    }

    private static ReportRow conflictRow(JsonNode conflict) {
        StringBuilder where = new StringBuilder();
        String date = conflict.path("date").asString("");
        if (!date.isEmpty()) {
            where.append(formatDate(date, DAY));
        }
        if (conflict.has("pair") && !conflict.path("pair").isNull()) {
            where.append(where.isEmpty() ? "" : ", ").append("пара ").append(conflict.path("pair").asInt());
        }
        String message = conflict.path("message").asString("Конфлікт із заміною.");
        return ReportRow.of(Severity.ERROR, RowType.PERIOD, conflict.path("kind").asString(null), null,
                where.isEmpty() ? message : message + " (" + where + ")");
    }

    private static String audience(JsonNode item, AcademicCalendarDraft.Period period) {
        List<String> groups = new ArrayList<>();
        item.path("groupCodes").forEach(code -> groups.add(code.asString("")));
        if (!groups.isEmpty()) {
            return groups.size() <= 5 ? String.join(", ", groups)
                    : String.join(", ", groups.subList(0, 5)) + " і ще " + (groups.size() - 5);
        }
        return period == null ? "" : NoteTemplate.audience(period.scope(), period.groupCodes(), period.courses());
    }

    private static String range(JsonNode from, JsonNode to) {
        return formatDate(from.asString(""), SHORT) + "–" + formatDate(to.asString(""), SHORT);
    }

    private static String formatDate(String iso, DateTimeFormatter format) {
        try {
            return format.format(LocalDate.parse(iso));
        } catch (DateTimeParseException e) {
            return iso;
        }
    }

    private static ReportRow unreachable() {
        return ReportRow.of(Severity.ERROR, RowType.PERIOD, null, null, "Сервер розкладу недоступний — перевірку "
                + "періодів не виконано. Збережіть параметри імпорту ще раз, коли він буде доступний.");
    }

    private static ReportRow dryRunFailed(CoreImportException e) {
        if (e.status() == 0) {
            return unreachable();
        }
        if (e.status() == 404 || e.status() == 405) {
            return ReportRow.of(Severity.ERROR, RowType.PERIOD, null, null, "Сервер розкладу ще не вміє імпортувати "
                    + "періоди графіка: оновіть його, потім збережіть параметри імпорту ще раз.");
        }
        return ReportRow.of(Severity.ERROR, RowType.PERIOD, null, null, "Перевірка на сервері розкладу: "
                + e.getMessage());
    }

    private AcademicCalendarDraft draft(JsonNode json) {
        return jsonMapper.treeToValue(json, AcademicCalendarDraft.class);
    }
}
