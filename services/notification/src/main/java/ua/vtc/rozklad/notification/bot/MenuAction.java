package ua.vtc.rozklad.notification.bot;

import java.util.Locale;
import java.util.Optional;

/**
 * Buttons of the main keyboard (UI.md §5). A pressed button arrives as its text, so text typed by
 * hand («сьогодні», «чисельник») works too.
 */
public enum MenuAction {
    TODAY("📅 Сьогодні"),
    TOMORROW("📆 Завтра"),
    MY_GROUP("👥 Моя група"),
    FIND("🔎 Знайти"),
    BELLS("🔔 Дзвінки"),
    PARITY("🔢 Чисельник?"),
    TEACHER_TOMORROW("🧑‍🏫 Мої пари завтра"),
    ABSENCE("🤒 Відсутність");

    private static final Locale UK = Locale.forLanguageTag("uk");

    private final String label;

    MenuAction(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Optional<MenuAction> match(String text) {
        MenuAction action = switch (normalize(text)) {
            case "сьогодні" -> TODAY;
            case "завтра" -> TOMORROW;
            case "моя група" -> MY_GROUP;
            case "знайти", "пошук" -> FIND;
            case "дзвінки", "розклад дзвінків" -> BELLS;
            case "чисельник", "знаменник", "чисельник чи знаменник" -> PARITY;
            case "мої пари завтра" -> TEACHER_TOMORROW;
            case "відсутність", "повідомити про відсутність" -> ABSENCE;
            default -> null;
        };
        return Optional.ofNullable(action);
    }

    /** Lowercase letters, digits and single spaces only (emoji and punctuation dropped). */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(UK)
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }
}
