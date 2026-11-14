package ua.vtc.rozklad.notification.bot;

import static ua.vtc.rozklad.notification.text.Html.b;
import static ua.vtc.rozklad.notification.text.Html.escape;
import static ua.vtc.rozklad.notification.text.Html.i;
import static ua.vtc.rozklad.notification.text.Html.s;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import ua.vtc.rozklad.notification.core.CoreDtos.GroupRef;
import ua.vtc.rozklad.notification.core.CoreDtos.LessonItem;
import ua.vtc.rozklad.notification.core.CoreDtos.Practice;
import ua.vtc.rozklad.notification.core.CoreDtos.ScheduleDay;
import ua.vtc.rozklad.notification.core.CoreQueries.DaySchedule;
import ua.vtc.rozklad.notification.core.OwnerType;
import ua.vtc.rozklad.notification.text.Plurals;
import ua.vtc.rozklad.notification.text.Reasons;
import ua.vtc.rozklad.notification.text.TeacherLabels;
import ua.vtc.rozklad.notification.text.UaDates;

/**
 * A day of a group, teacher or room as a compact Telegram card: pair number and time, what and
 * who, and for changed lessons what it replaced (struck through) and why. Teachers read as
 * «Прізвище І. Б.» ({@link TeacherLabels}, completed from the public teacher list when it has the names).
 */
public final class ScheduleCards {

    private static final Locale UK = Locale.forLanguageTag("uk");

    private ScheduleCards() {
    }

    public static String day(DaySchedule schedule) {
        return day(schedule, null);
    }

    /**
     * {@code nextStudyDay} (may be {@code null}) is named under «Навчання немає», so a weekend or a
     * holiday card says where the lessons continue.
     */
    public static String day(DaySchedule schedule, LocalDate nextStudyDay) {
        return day(schedule, nextStudyDay, TeacherLabels.SHORT_FORM);
    }

    public static String day(DaySchedule schedule, LocalDate nextStudyDay, TeacherLabels teachers) {
        ScheduleDay day = schedule.day();
        StringBuilder text = new StringBuilder()
                .append(icon(schedule.ownerType())).append(' ')
                .append(b(label(schedule, teachers)))
                .append(" · ").append(escape(UaDates.weekdayDayMonth(day.date())));
        String parity = UaDates.parity(day.parity());
        if (parity != null) {
            text.append('\n').append(i(UaDates.capitalize(parity)));
        }
        text.append("\n\n");
        String practices = practices(day.practices(), schedule.ownerType(), teachers);
        if (day.lessons().isEmpty()) {
            text.append(practices != null ? practices : day.studyDay() ? "Пар немає 🙂" : "🌿 Навчання немає");
            if (!day.studyDay() && nextStudyDay != null) {
                text.append("\nНаступний навчальний день: ").append(b(dayName(nextStudyDay)));
            }
        } else {
            text.append(day.lessons().stream()
                    .sorted(Comparator.comparingInt(LessonItem::pair).thenComparingInt(LessonItem::subgroup))
                    .map(lesson -> lesson(lesson, schedule.ownerType(), teachers))
                    .collect(Collectors.joining("\n\n")));
            if (practices != null) {
                text.append("\n\n").append(practices);
            }
        }
        String note = noteWithoutPractices(day);
        if (note != null) {
            text.append("\n\nℹ️ ").append(escape(note));
        }
        return text.toString();
    }

    /**
     * A group at practice: «🛠 Практика: назва» with its supervisors; a teacher: «🛠 Керівництво
     * практикою: назва, група». {@code null} when the day has no practice.
     */
    static String practices(List<Practice> practices, OwnerType perspective, TeacherLabels teachers) {
        if (practices.isEmpty() || perspective == OwnerType.ROOM) {
            return null;
        }
        return practices.stream().map(practice -> {
            if (perspective == OwnerType.TEACHER) {
                String whom = whom(practice);
                return "🛠 " + b("Керівництво практикою") + ": "
                        + escape(whom.isEmpty() ? practice.title() : practice.title() + ", " + whom);
            }
            String text = "🛠 " + b(escape("Практика: " + practice.title()));
            List<String> names = practice.supervisors().stream()
                    .map(teacher -> teachers.label(teacher.id(), teacher.label()))
                    .toList();
            return names.isEmpty() ? text
                    : text + "\n" + (names.size() == 1 ? "Керівник: " : "Керівники: ") + escape(String.join(", ", names));
        }).collect(Collectors.joining("\n\n"));
    }

    /** «2КІ3, 2КІ4» or «2 курс»; empty for a college-wide practice. */
    private static String whom(Practice practice) {
        if (!practice.groups().isEmpty()) {
            return practice.groups().stream().map(GroupRef::code).collect(Collectors.joining(", "));
        }
        if (!practice.courses().isEmpty()) {
            return practice.courses().stream().map(String::valueOf).collect(Collectors.joining(", ")) + " курс";
        }
        return "";
    }

    /** The day's note without the practice titles the practice block already shows. */
    private static String noteWithoutPractices(ScheduleDay day) {
        if (day.note() == null || day.note().isBlank()) {
            return null;
        }
        Set<String> shown = day.practices().stream().map(Practice::title).collect(Collectors.toSet());
        String rest = Arrays.stream(day.note().split("; "))
                .filter(part -> !shown.contains(part.strip()))
                .collect(Collectors.joining("; "));
        return rest.isBlank() ? null : rest;
    }

    /** {@code пн, 9 листопада}. */
    static String dayName(LocalDate date) {
        return UaDates.weekdayShort(date).toLowerCase(UK) + ", " + UaDates.dayMonth(date);
    }

    /** Short summary for inline results: «5 пар · 1 заміна». */
    public static String summary(ScheduleDay day) {
        if (day.lessons().isEmpty()) {
            if (!day.practices().isEmpty()) {
                // група на практиці не має навчального дня, у керівника день навчальний
                return day.studyDay() ? "Керівництво практикою" : "Практика";
            }
            return day.studyDay() ? "Пар немає" : "Навчання немає";
        }
        long pairs = day.lessons().stream().filter(lesson -> !"CANCELLED".equals(kind(lesson)))
                .map(LessonItem::pair).distinct().count();
        long changes = day.lessons().stream().filter(lesson -> !"REGULAR".equals(kind(lesson))).count();
        String text = pairs + " " + Plurals.of(pairs, "пара", "пари", "пар");
        return changes == 0 ? text : text + " · змін: " + changes;
    }

    static String lesson(LessonItem lesson, OwnerType perspective, TeacherLabels teachers) {
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
        String what = what(lesson, perspective, teachers);
        text.append('\n').append("CANCELLED".equals(kind) ? s(what) : escape(what));
        String original = original(lesson, kind, perspective, teachers);
        if (original != null) {
            text.append('\n').append(original);
        }
        if (lesson.reasonText() != null && !lesson.reasonText().isBlank()) {
            text.append('\n').append(i(UaDates.capitalize(Reasons.inWords(lesson.reasonText()))));
        }
        return text.toString();
    }

    /** Who/what/where from the owner's point of view (the owner itself is not repeated). */
    private static String what(LessonItem lesson, OwnerType perspective, TeacherLabels teachers) {
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
            parts.add(teachers.label(lesson.teacher().id(), lesson.teacher().label()));
        }
        if (perspective != OwnerType.ROOM && lesson.room() != null && lesson.room().name() != null) {
            parts.add("ауд. " + lesson.room().name());
        }
        return parts.isEmpty() ? "—" : String.join(" · ", parts);
    }

    private static String original(LessonItem lesson, String kind, OwnerType perspective, TeacherLabels teachers) {
        JsonNode original = lesson.original();
        if (original == null || original.isNull() || original.isMissingNode()) {
            return null;
        }
        return switch (kind) {
            case "SUBSTITUTED" -> {
                List<String> parts = new ArrayList<>(2);
                addLabel(parts, original.get("discipline"));
                if (perspective != OwnerType.TEACHER) {
                    JsonNode node = original.get("teacher");
                    String teacher = label(node);
                    if (teacher != null) {
                        parts.add(teachers.label(idOf(node), teacher));
                    }
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

    /** The {@code id} of a ref node, or {@code null} for plain text or a malformed id. */
    private static UUID idOf(JsonNode node) {
        JsonNode id = node == null ? null : node.get("id");
        if (id == null || !id.isString()) {
            return null;
        }
        try {
            return UUID.fromString(id.asString());
        } catch (IllegalArgumentException e) {
            return null;
        }
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

    private static String label(DaySchedule schedule, TeacherLabels teachers) {
        if (schedule.ownerLabel() != null && !schedule.ownerLabel().isBlank()) {
            return schedule.ownerType() == OwnerType.TEACHER
                    ? teachers.label(schedule.ownerId(), schedule.ownerLabel())
                    : schedule.ownerLabel();
        }
        return switch (schedule.ownerType()) {
            case GROUP -> "Група";
            case TEACHER -> "Викладач";
            case ROOM -> "Аудиторія";
        };
    }
}
