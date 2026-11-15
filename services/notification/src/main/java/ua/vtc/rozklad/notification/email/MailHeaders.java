package ua.vtc.rozklad.notification.email;

/**
 * Header values built from data (subjects, display names) never carry a line break: control characters become
 * spaces, runs of spaces collapse and the value is capped, so no data can add a header of its own.
 */
public final class MailHeaders {

    public static final int MAX_LENGTH = 200;

    private MailHeaders() {
    }

    public static String clean(String value) {
        return clean(value, MAX_LENGTH);
    }

    public static String clean(String value, int max) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        boolean space = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean blank = Character.isWhitespace(c) || Character.isISOControl(c) || c == ' ' || c == ' '
                    || Character.getType(c) == Character.FORMAT;
            if (blank) {
                space = !out.isEmpty();
                continue;
            }
            if (space) {
                out.append(' ');
                space = false;
            }
            out.append(c);
        }
        String cleaned = out.toString();
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max - 1).stripTrailing() + "…";
    }
}
