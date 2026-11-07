package ua.vtc.rozklad.importer.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON schemas of the structured outputs. Structured outputs need {@code additionalProperties:
 * false} on every object and support no numeric/length constraints, so every property is required
 * and optional values are {@code anyOf [type, null]}; ranges are validated in Java afterwards.
 */
public final class AiSchemas {

    private AiSchemas() {
    }

    /** Розпорядження: header, table rows of both sections, notes, signature. */
    public static Map<String, Object> order() {
        Map<String, Object> row = object(props(
                "section", enumOf("CHANGES", "ROOM_CHANGES"),
                "groupCode", string(),
                "pairLabel", string(),
                "rowType", enumOf("REPLACEMENT", "CANCELLATION", "ADDITIONAL", "BLOCK", "FINISHED", "AS_SCHEDULED", "ROOM_CHANGE"),
                "fromDiscipline", nullableString(),
                "fromTeacher", nullableString(),
                "reasonText", nullableString(),
                "toDiscipline", nullableString(),
                "toTeacher", nullableString(),
                "roomText", nullableString()));
        return object(props(
                "number", nullable("integer"),
                "kind", enumOf("ORDER", "AMENDMENT"),
                "parentNumber", nullable("integer"),
                "date", nullableString(),
                "weekdayLabel", nullableString(),
                "parityLabel", nullableString(),
                "rows", arrayOf(row),
                "notes", arrayOf(string()),
                "signatoryPosition", nullableString(),
                "signatoryName", nullableString(),
                "warnings", arrayOf(string())));
    }

    /** Навчальний план: rows of one semester. */
    public static Map<String, Object> curriculum() {
        Map<String, Object> row = object(props(
                "discipline", string(),
                "hoursPerWeek", nullable("number"),
                "hoursPerSemester", nullable("integer"),
                "totalHours", nullable("integer"),
                "classroomHours", nullable("integer"),
                "lectures", nullable("integer"),
                "practice", nullable("integer"),
                "labs", nullable("integer"),
                "controlForm", enumOf("EXAM", "CREDIT", "NONE", "UNKNOWN")));
        return object(props(
                "semester", integer(),
                "weeks", nullable("integer"),
                "rows", arrayOf(row),
                "totalHoursPerWeek", nullable("number"),
                "warnings", arrayOf(string())));
    }

    /** Структура навчальної дисципліни (fallback when the table is missing). */
    public static Map<String, Object> programHours() {
        return object(props(
                "disciplineName", nullableString(),
                "totalHours", nullable("integer"),
                "classroomHours", nullable("integer"),
                "selfStudyHours", nullable("integer"),
                "lectures", nullable("integer"),
                "practice", nullable("integer"),
                "labs", nullable("integer"),
                "seminars", nullable("integer"),
                "exam", bool(),
                "credit", bool(),
                "semester", nullable("integer"),
                "hoursPerWeek", nullable("number"),
                "warnings", arrayOf(string())));
    }

    /** Відсутність викладача з вільного тексту. */
    public static Map<String, Object> absence() {
        Map<String, Object> pairs = Map.of("anyOf", List.of(Map.of("type", "array", "items", integer()), Map.of("type", "null")));
        return object(props(
                "dateFrom", date(),
                "dateTo", date(),
                "pairs", pairs,
                "reasonCategory", enumOf("SICK_LEAVE", "VACATION", "BUSINESS_TRIP", "PERSONAL", "OTHER"),
                "confidence", Map.of("type", "number"),
                "clarification", nullableString()));
    }

    // ───────────────────────── мінімальний DSL схем ─────────────────────────

    private static Map<String, Object> props(Object... keysAndValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            result.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return result;
    }

    private static Map<String, Object> object(Map<String, Object> properties) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "object");
        result.put("properties", properties);
        result.put("required", List.copyOf(properties.keySet()));
        result.put("additionalProperties", false);
        return result;
    }

    private static Map<String, Object> string() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> integer() {
        return Map.of("type", "integer");
    }

    private static Map<String, Object> bool() {
        return Map.of("type", "boolean");
    }

    private static Map<String, Object> date() {
        return Map.of("type", "string", "format", "date");
    }

    private static Map<String, Object> nullableString() {
        return nullable("string");
    }

    /** {@code anyOf [type, null]} — the documented way to express an optional value in structured outputs. */
    private static Map<String, Object> nullable(String type) {
        return Map.of("anyOf", List.of(Map.of("type", type), Map.of("type", "null")));
    }

    private static Map<String, Object> enumOf(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }

    private static Map<String, Object> arrayOf(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }
}
