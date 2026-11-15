package ua.vtc.rozklad.importer.job;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import ua.vtc.rozklad.importer.text.GroupCode;
import ua.vtc.rozklad.importer.text.TextNormalizer;
import ua.vtc.rozklad.importer.web.InvalidRequestException;

/**
 * Typed view of the upload's {@code options} JSON (CONTRACTS §5 {@code options?}):
 * <ul>
 *   <li>{@code sheet} — TIMETABLE_XLSX sheet name (default «Розклад друк»); ACADEMIC_CALENDAR_XLSX sheet
 *       (default: the first sheet with a «ГРУПА» header and week columns);</li>
 *   <li>{@code versionName} — name of the draft timetable version created on apply;</li>
 *   <li>{@code semesterNumber} — CURRICULUM: semester of the plan to extract (1..8);</li>
 *   <li>{@code groupCodes} — DISCIPLINE_PROGRAM / CURRICULUM: groups that get load items on apply;</li>
 *   <li>{@code hoursPerWeek} — DISCIPLINE_PROGRAM: weekly hours when the program does not say;</li>
 *   <li>{@code academicYear} — ACADEMIC_CALENDAR_XLSX: «2026/2027» when the file's title does not say;</li>
 *   <li>{@code skipCodes}, {@code skipGroups} — ACADEMIC_CALENDAR_XLSX: codes and groups that are not imported;</li>
 *   <li>{@code titles}, {@code rooms} — ACADEMIC_CALENDAR_XLSX: period title and room name per kind code; when
 *       the key is absent the owner's defaults apply ({@link #DEFAULT_TITLES}, {@link #DEFAULT_ROOMS}), an
 *       empty object means none.</li>
 * </ul>
 * Unknown keys are rejected so a typo does not silently change nothing.
 */
public record JobOptions(String sheet, String versionName, Integer semesterNumber, List<String> groupCodes,
                         BigDecimal hoursPerWeek, String academicYear, List<String> skipCodes, List<String> skipGroups,
                         Map<String, String> titles, Map<String, String> rooms) {

    private static final Set<String> KEYS = Set.of("sheet", "versionName", "semesterNumber", "groupCodes", "hoursPerWeek",
            "academicYear", "skipCodes", "skipGroups", "titles", "rooms");
    /** Owner's decision (15.11.2026): «НС» is the course «Основи національного спротиву» in room 401. */
    public static final Map<String, String> DEFAULT_TITLES = Map.of("НС", "Основи національного спротиву");
    public static final Map<String, String> DEFAULT_ROOMS = Map.of("НС", "401");
    private static final Pattern ACADEMIC_YEAR = Pattern.compile("^(\\d{4})/(\\d{4})$");
    private static final Pattern PERIOD_CODE = Pattern.compile("^[\\p{L}\\p{N}]{1,10}$");
    private static final String LATIN_UPPER = "ABCEHIKMOPTXY";
    private static final String CYRILLIC_UPPER = "АВСЕНІКМОРТХУ";
    private static final int MAX_LIST = 100;
    private static final int MAX_TITLE = 200;
    private static final int MAX_ROOM = 50;

    public JobOptions {
        groupCodes = groupCodes == null ? List.of() : List.copyOf(groupCodes);
        skipCodes = skipCodes == null ? List.of() : List.copyOf(skipCodes);
        skipGroups = skipGroups == null ? List.of() : List.copyOf(skipGroups);
        titles = titles == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(titles));
        rooms = rooms == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(rooms));
    }

    /** Options of the kinds that read no academic calendar. */
    public JobOptions(String sheet, String versionName, Integer semesterNumber, List<String> groupCodes,
                      BigDecimal hoursPerWeek) {
        this(sheet, versionName, semesterNumber, groupCodes, hoursPerWeek, null, List.of(), List.of(), null, null);
    }

    public static JobOptions none() {
        return new JobOptions(null, null, null, List.of(), null);
    }

    /** Period titles per kind code: the given ones, or the owner's defaults when {@code titles} is absent. */
    public Map<String, String> effectiveTitles() {
        return titles == null ? DEFAULT_TITLES : titles;
    }

    /** Room names per kind code: the given ones, or the owner's defaults when {@code rooms} is absent. */
    public Map<String, String> effectiveRooms() {
        return rooms == null ? DEFAULT_ROOMS : rooms;
    }

    /**
     * A calendar code as it is compared everywhere: no spaces, upper case, Latin look-alikes as Cyrillic
     * letters («HП» → «НП», «K» → «К»), so a code typed in another keyboard layout still finds its kind.
     */
    public static String periodCode(String raw) {
        String text = TextNormalizer.clean(raw).replace(" ", "").toUpperCase(TextNormalizer.UK);
        StringBuilder folded = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int latin = LATIN_UPPER.indexOf(c);
            folded.append(latin >= 0 ? CYRILLIC_UPPER.charAt(latin) : c);
        }
        return folded.toString();
    }

    /** Parses and validates; field errors are reported as {@code options.<key>}. */
    public static JobOptions from(JsonNode json) {
        if (json == null || json.isNull() || json.isMissingNode()) {
            return none();
        }
        if (!json.isObject()) {
            throw InvalidRequestException.field("options", "Параметри мають бути JSON-об'єктом.");
        }
        List<Map.Entry<String, String>> errors = new ArrayList<>();
        for (String name : json.propertyNames()) {
            if (!KEYS.contains(name)) {
                errors.add(Map.entry("options." + name, "Невідомий параметр. Допустимі: " + String.join(", ", KEYS.stream().sorted().toList()) + "."));
            }
        }
        String sheet = text(json, "sheet", 100, errors);
        String versionName = text(json, "versionName", 100, errors);
        Integer semesterNumber = null;
        JsonNode semester = json.get("semesterNumber");
        if (semester != null && !semester.isNull()) {
            if (!semester.isIntegralNumber() || semester.asInt() < 1 || semester.asInt() > 8) {
                errors.add(Map.entry("options.semesterNumber", "Номер семестру навчального плану — ціле число від 1 до 8."));
            } else {
                semesterNumber = semester.asInt();
            }
        }
        List<String> groups = new ArrayList<>();
        JsonNode codes = json.get("groupCodes");
        if (codes != null && !codes.isNull()) {
            if (!codes.isArray()) {
                errors.add(Map.entry("options.groupCodes", "Коди груп — масив рядків, наприклад [\"4КІ1\", \"4КІ2\"]."));
            } else {
                Set<String> unique = new LinkedHashSet<>();
                for (JsonNode code : codes) {
                    var parsed = code.isString() ? GroupCode.parse(code.asString()) : Optional.<GroupCode>empty();
                    if (parsed.isEmpty()) {
                        errors.add(Map.entry("options.groupCodes", "«" + code.asString() + "» — не код групи."));
                    } else {
                        unique.add(parsed.get().code());
                    }
                }
                groups.addAll(unique);
            }
        }
        BigDecimal hoursPerWeek = null;
        JsonNode hours = json.get("hoursPerWeek");
        if (hours != null && !hours.isNull()) {
            if (!hours.isNumber() || hours.decimalValue().signum() <= 0 || hours.decimalValue().compareTo(BigDecimal.valueOf(40)) > 0) {
                errors.add(Map.entry("options.hoursPerWeek", "Годин на тиждень — додатне число не більше 40."));
            } else {
                hoursPerWeek = hours.decimalValue();
            }
        }
        String academicYear = academicYear(json, errors);
        List<String> skipCodes = codeList(json, errors);
        List<String> skipGroups = groupList(json, errors);
        Map<String, String> titles = codeMap(json, "titles", MAX_TITLE, "Назви періодів — об'єкт «код виду → назва», "
                + "наприклад {\"НС\": \"Основи національного спротиву\"}.", errors);
        Map<String, String> rooms = codeMap(json, "rooms", MAX_ROOM, "Аудиторії періодів — об'єкт «код виду → "
                + "аудиторія», наприклад {\"НС\": \"401\"}.", errors);
        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }
        return new JobOptions(sheet, versionName, semesterNumber, groups, hoursPerWeek, academicYear, skipCodes,
                skipGroups, titles, rooms);
    }

    public JsonNode toJson() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        if (sheet != null) {
            node.put("sheet", sheet);
        }
        if (versionName != null) {
            node.put("versionName", versionName);
        }
        if (semesterNumber != null) {
            node.put("semesterNumber", semesterNumber);
        }
        if (!groupCodes.isEmpty()) {
            var array = node.putArray("groupCodes");
            groupCodes.forEach(array::add);
        }
        if (hoursPerWeek != null) {
            node.put("hoursPerWeek", hoursPerWeek);
        }
        if (academicYear != null) {
            node.put("academicYear", academicYear);
        }
        if (!skipCodes.isEmpty()) {
            var array = node.putArray("skipCodes");
            skipCodes.forEach(array::add);
        }
        if (!skipGroups.isEmpty()) {
            var array = node.putArray("skipGroups");
            skipGroups.forEach(array::add);
        }
        if (titles != null) {
            ObjectNode object = node.putObject("titles");
            titles.forEach(object::put);
        }
        if (rooms != null) {
            ObjectNode object = node.putObject("rooms");
            rooms.forEach(object::put);
        }
        return node;
    }

    private static String text(JsonNode json, String key, int maxLength, List<Map.Entry<String, String>> errors) {
        JsonNode value = json.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString() || value.asString().isBlank() || value.asString().length() > maxLength) {
            errors.add(Map.entry("options." + key, "Непорожній рядок до " + maxLength + " символів."));
            return null;
        }
        return value.asString().strip();
    }

    private static String academicYear(JsonNode json, List<Map.Entry<String, String>> errors) {
        JsonNode year = json.get("academicYear");
        if (year == null || year.isNull()) {
            return null;
        }
        Matcher matcher = year.isString() ? ACADEMIC_YEAR.matcher(year.asString().strip()) : null;
        if (matcher == null || !matcher.matches()
                || Integer.parseInt(matcher.group(2)) != Integer.parseInt(matcher.group(1)) + 1) {
            errors.add(Map.entry("options.academicYear", "Навчальний рік — два роки поспіль через «/», наприклад «2026/2027»."));
            return null;
        }
        return matcher.group();
    }

    private static List<String> codeList(JsonNode json, List<Map.Entry<String, String>> errors) {
        JsonNode value = json.get("skipCodes");
        if (value == null || value.isNull()) {
            return List.of();
        }
        String message = "Коди, які не імпортувати, — масив кодів графіка (до " + MAX_LIST + "), наприклад [\"Р\"].";
        if (!value.isArray() || value.size() > MAX_LIST) {
            errors.add(Map.entry("options.skipCodes", message));
            return List.of();
        }
        Set<String> codes = new LinkedHashSet<>();
        for (JsonNode code : value) {
            String folded = code.isString() ? periodCode(code.asString()) : "";
            if (!PERIOD_CODE.matcher(folded).matches()) {
                errors.add(Map.entry("options.skipCodes", message));
                return List.of();
            }
            codes.add(folded);
        }
        return List.copyOf(codes);
    }

    private static List<String> groupList(JsonNode json, List<Map.Entry<String, String>> errors) {
        JsonNode value = json.get("skipGroups");
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray() || value.size() > MAX_LIST) {
            errors.add(Map.entry("options.skipGroups", "Групи, які не імпортувати, — масив кодів (до " + MAX_LIST
                    + "), наприклад [\"4КІ1\"]."));
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (JsonNode code : value) {
            Optional<GroupCode> parsed = code.isString() ? GroupCode.parse(code.asString().replaceAll("[-–—]", ""))
                    : Optional.empty();
            if (parsed.isEmpty()) {
                errors.add(Map.entry("options.skipGroups", "«" + code.asString() + "» — не код групи."));
            } else {
                unique.add(parsed.get().code());
            }
        }
        return List.copyOf(unique);
    }

    private static Map<String, String> codeMap(JsonNode json, String key, int maxLength, String message,
                                               List<Map.Entry<String, String>> errors) {
        JsonNode value = json.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isObject() || value.size() > MAX_LIST) {
            errors.add(Map.entry("options." + key, message));
            return null;
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : value.propertyNames()) {
            JsonNode text = value.get(name);
            String code = periodCode(name);
            if (!PERIOD_CODE.matcher(code).matches() || !text.isString() || text.asString().isBlank()
                    || text.asString().strip().length() > maxLength) {
                errors.add(Map.entry("options." + key, message + " Значення — непорожній рядок до " + maxLength
                        + " символів."));
                return null;
            }
            result.put(code, TextNormalizer.clean(text.asString()));
        }
        return result;
    }
}
