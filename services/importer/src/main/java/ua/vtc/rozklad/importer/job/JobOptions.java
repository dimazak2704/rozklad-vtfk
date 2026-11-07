package ua.vtc.rozklad.importer.job;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import ua.vtc.rozklad.importer.text.GroupCode;
import ua.vtc.rozklad.importer.web.InvalidRequestException;

/**
 * Typed view of the upload's {@code options} JSON (CONTRACTS §5 {@code options?}):
 * <ul>
 *   <li>{@code sheet} — TIMETABLE_XLSX sheet name (default «Розклад друк»);</li>
 *   <li>{@code versionName} — name of the draft timetable version created on apply;</li>
 *   <li>{@code semesterNumber} — CURRICULUM: semester of the plan to extract (1..8);</li>
 *   <li>{@code groupCodes} — DISCIPLINE_PROGRAM / CURRICULUM: groups that get load items on apply;</li>
 *   <li>{@code hoursPerWeek} — DISCIPLINE_PROGRAM: weekly hours when the program does not say.</li>
 * </ul>
 * Unknown keys are rejected so a typo does not silently change nothing.
 */
public record JobOptions(String sheet, String versionName, Integer semesterNumber, List<String> groupCodes,
                         BigDecimal hoursPerWeek) {

    private static final Set<String> KEYS = Set.of("sheet", "versionName", "semesterNumber", "groupCodes", "hoursPerWeek");

    public JobOptions {
        groupCodes = groupCodes == null ? List.of() : List.copyOf(groupCodes);
    }

    public static JobOptions none() {
        return new JobOptions(null, null, null, List.of(), null);
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
                    var parsed = code.isString() ? GroupCode.parse(code.asString()) : java.util.Optional.<GroupCode>empty();
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
        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }
        return new JobOptions(sheet, versionName, semesterNumber, groups, hoursPerWeek);
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
}
