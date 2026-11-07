package ua.vtc.rozklad.notification.bot;

import static ua.vtc.rozklad.notification.text.Html.b;
import static ua.vtc.rozklad.notification.text.Html.escape;
import static ua.vtc.rozklad.notification.text.Html.i;
import static ua.vtc.rozklad.notification.text.Html.s;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import ua.vtc.rozklad.notification.core.CoreDtos.LessonItem;
import ua.vtc.rozklad.notification.core.CoreDtos.ScheduleDay;
import ua.vtc.rozklad.notification.core.CoreQueries.DaySchedule;
import ua.vtc.rozklad.notification.core.OwnerType;
import ua.vtc.rozklad.notification.text.Plurals;
import ua.vtc.rozklad.notification.text.UaDates;

/**
 * A day of a group, teacher or room as a compact Telegram card: pair number and time, what and
 * who, and for changed lessons what it replaced (struck through) and why.
 */
public final class ScheduleCards {

    private static final Locale UK = Locale.forLanguageTag("uk");

    private ScheduleCards() {
    }

    public static String day(DaySchedule schedule) {
        ScheduleDay day = schedule.day();
        StringBuilder text = new StringBuilder()
                .append(icon(schedule.ownerType())).append(' ')
                .append(b(label(schedule)))
                .append(" · ").append(escape(UaDates.weekdayDayMonth(day.date())));
        String parity = UaDates.parity(day.parity());
        if (parity != null) {
            text.append('\n').append(i(UaDates.capitalize(parity)));
        }
        text.append("\n\n");
        if (day.lessons().isEmpty()) {
            text.append(day.studyDay() ? "Пар немає 🙂" : "🌿 Навчання немає");
        } else {
            text.append(day.lessons().stream()
                    .sorted(Comparator.comparingInt(LessonItem::pair).thenComparingInt(LessonItem::subgroup))
                    .map(lesson -> lesson(lesson, schedule.ownerType()))
                    .collect(Collectors.joining("\n\n")));
        }
        if (day.note() != null && !day.note().isBlank()) {
            text.append("\n\nℹ️ ").append(escape(day.note()));
        }
        return text.toString();
    }

    /** Short summary for inline results: «5 пар · 1 заміна». */
    public static String summary(ScheduleDay day) {
        if (day.lessons().isEmpty()) {
            return day.studyDay() ? "Пар немає" : "Навчання немає";
        }
        long pairs = day.lessons().stream().filter(lesson -> !"CANCELLED".equals(kind(lesson)))
                .map(LessonItem::pair).distinct().count();
        long changes = day.lessons().stream().filter(lesson -> !"REGULAR".equals(kind(lesson))).count();
        String text = pairs + " " + Plurals.of(pairs, "пара", "пари", "пар");
        return changes == 0 ? text : text + " · змін: " + changes;
    }

    static String lesson(LessonItem lesson, OwnerType perspective) {
        String kind = kind(lesson);
        StringBuilder text = new StringBuilder(b(Integer.toString(lesson.pair())));
        String time = UaDates.timeRange(lesson.startsAt(), lesson.endsAt());
        if (!time.isEmpty()) {
            text.append(" · ").append(time);
        }
        text.append(tag(kind));
        if (lesson.online()) {
            text.append(" · 💻 онлайн");
        }
        String what = what(lesson, perspective);
        text.append('\n').append("CANCELLED".equals(kind) ? s(what) : escape(what));
        String original = original(lesson, kind, perspective);
        if (original != null) {
            text.append('\n').append(original);
        }
        if (lesson.reasonText() != null && !lesson.reasonText().isBlank()) {
            text.append('\n').append(i(lesson.reasonText()));
        }
        return text.toString();
    }

    /** Who/what/where from the owner's point of view (the owner itself is not repeated). */
    private static String what(LessonItem lesson, OwnerType perspective) {
        List<String> parts = new ArrayList<>(4);
        String group = lesson.group() == null ? null : lesson.group().code();
        if (perspective != OwnerType.GROUP && group != null) {
            parts.add(group + subgroup(lesson));
        }
        String discipline = lesson.discipline() == null ? null : lesson.discipline().label();
        if (discipline != null) {
            parts.add(perspective == OwnerType.GROUP ? discipline + subgroup(lesson) : discipline);
        }
        if (perspective != OwnerType.TEACHER && lesson.teacher() != null && lesson.teacher().label() != null) {
            parts.add(lesson.teacher().label());
        }
        if (perspective != OwnerType.ROOM && lesson.room() != null && lesson.room().name() != null) {
            parts.add("ауд. " + lesson.room().name());
        }
        return parts.isEmpty() ? "—" : String.join(" · ", parts);
    }

    private static String original(LessonItem lesson, String kind, OwnerType perspective) {
        JsonNode original = lesson.original();
        if (original == null || original.isNull() || original.isMissingNode()) {
            return null;
        }
        return switch (kind) {
            case "SUBSTITUTED" -> {
                List<String> parts = new ArrayList<>(2);
                addLabel(parts, original.get("discipline"));
                if (perspective != OwnerType.TEACHER) {
                    addLabel(parts, original.get("teacher"));
                }
                yield parts.isEmpty() ? null : "замість " + s(String.join(" · ", parts));
            }
            case "MOVED" -> original.hasNonNull("pair")
                    ? i("перенесено з " + original.get("pair").asString() + " пари")
                    : null;
            case "ROOM_CHANGED" -> {
                String room = label(original.get("room"));
                yield room == null ? null : "замість ауд. " + s(room);
            }
            default -> null;
        };
    }

    private static void addLabel(List<String> parts, JsonNode node) {
        String label = label(node);
        if (label != null) {
            parts.add(label);
        }
    }

    /** A ref ({@code {shortName|displayName|name|code}}) or plain text. */
    static String label(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isString()) {
            String text = node.asString();
            return text.isBlank() ? null : text;
        }
        for (String field : List.of("shortName", "displayName", "name", "label", "code")) {
            JsonNode value = node.get(field);
            if (value != null && value.isString() && !value.asString().isBlank()) {
                return value.asString();
            }
        }
        return null;
    }

    private static String tag(String kind) {
        return switch (kind) {
            case "SUBSTITUTED" -> " · 🔁 заміна";
            case "CANCELLED" -> " · ❌ пари не буде";
            case "ADDITIONAL" -> " · ➕ додаткова";
            case "MOVED" -> " · ↪️ перенесено";
            case "ROOM_CHANGED" -> " · 🚪 інша аудиторія";
            case "BLOCK" -> " · 📌";
            default -> "";
        };
    }

    private static String subgroup(LessonItem lesson) {
        return lesson.subgroup() > 0 ? " (" + lesson.subgroup() + " підгр.)" : "";
    }

    private static String kind(LessonItem lesson) {
        return lesson.kind() == null ? "REGULAR" : lesson.kind().toUpperCase(UK);
    }

    private static String icon(OwnerType type) {
        return switch (type) {
            case GROUP -> "👥";
            case TEACHER -> "🧑‍🏫";
            case ROOM -> "🚪";
        };
    }

    private static String label(DaySchedule schedule) {
        if (schedule.ownerLabel() != null && !schedule.ownerLabel().isBlank()) {
            return schedule.ownerLabel();
        }
        return switch (schedule.ownerType()) {
            case GROUP -> "Група";
            case TEACHER -> "Викладач";
            case ROOM -> "Аудиторія";
        };
    }
}
