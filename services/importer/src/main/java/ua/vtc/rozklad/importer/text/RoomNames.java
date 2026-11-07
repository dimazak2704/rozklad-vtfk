package ua.vtc.rozklad.importer.text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ua.vtc.rozklad.contracts.RoomType;

/**
 * Canonical room names (DOMAIN §11): numbers as plain digits («307», «6»), the sports hall in any
 * spelling («с.з», «С.З.») as «с.з.», the sports ground as «с.п.».
 */
public final class RoomNames {

    public static final String GYM = "с.з.";
    public static final String SPORTS_GROUND = "с.п.";
    /** DOMAIN §1: the sports hall hosts several groups at once. */
    public static final int GYM_DEFAULT_MAX_PARALLEL = 3;

    private static final Pattern NUMBER = Pattern.compile("^(?:ауд\\.?|аудиторія|№)?\\s*(\\d{1,4}[а-яіїєґ]?)\\s*[.,]?$");

    private RoomNames() {
    }

    /** Canonical name; empty string for blank input. */
    public static String canonical(String raw) {
        String text = TextNormalizer.clean(raw);
        if (text.isEmpty()) {
            return "";
        }
        String key = TextNormalizer.abbreviationKey(text);
        if (key.equals("сз")) {
            return GYM;
        }
        if (key.equals("сп")) {
            return SPORTS_GROUND;
        }
        Matcher number = NUMBER.matcher(TextNormalizer.key(text));
        if (number.matches()) {
            return number.group(1);
        }
        return text;
    }

    public static RoomType type(String canonical) {
        return switch (canonical) {
            case GYM -> RoomType.GYM;
            case SPORTS_GROUND -> RoomType.SPORTS_GROUND;
            default -> RoomType.CLASSROOM;
        };
    }

    public static int defaultMaxParallel(String canonical) {
        return GYM.equals(canonical) ? GYM_DEFAULT_MAX_PARALLEL : 1;
    }

    /** Comparison key of a room name (case, dots and spaces ignored). */
    public static String key(String name) {
        return TextNormalizer.abbreviationKey(canonical(name));
    }
}
