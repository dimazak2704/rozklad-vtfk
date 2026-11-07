package ua.vtc.rozklad.notification.core;

import java.util.Locale;

/** Whose schedule: path segment of {@code /api/public/schedule/{type}/{id}} and a one-letter callback code. */
public enum OwnerType {
    GROUP("group", "g"),
    TEACHER("teacher", "t"),
    ROOM("room", "r");

    private final String path;
    private final String code;

    OwnerType(String path, String code) {
        this.path = path;
        this.code = code;
    }

    public String path() {
        return path;
    }

    public String code() {
        return code;
    }

    public static OwnerType fromCode(String code) {
        for (OwnerType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown owner code: " + code);
    }

    /** Search result type → owner; {@code null} for types the bot cannot show. */
    public static OwnerType fromSearchType(String type) {
        if (type == null) {
            return null;
        }
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "GROUP" -> GROUP;
            case "TEACHER" -> TEACHER;
            case "ROOM" -> ROOM;
            default -> null;
        };
    }
}
