package ua.vtc.rozklad.notification.bot;

import java.util.Locale;
import java.util.Optional;

/** {@code /today@scheduler_vtfk_bot arg} → name {@code today}, argument {@code arg}, mention. */
public record Command(String name, String argument, String botMention) {

    public static Optional<Command> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String trimmed = text.strip();
        if (!trimmed.startsWith("/") || trimmed.length() < 2) {
            return Optional.empty();
        }
        int space = indexOfWhitespace(trimmed);
        String head = space < 0 ? trimmed.substring(1) : trimmed.substring(1, space);
        String argument = space < 0 ? "" : trimmed.substring(space).strip();
        String name = head;
        String mention = null;
        int at = head.indexOf('@');
        if (at >= 0) {
            name = head.substring(0, at);
            mention = head.substring(at + 1);
        }
        if (name.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Command(name.toLowerCase(Locale.ROOT), argument, mention));
    }

    /** Commands in groups may name another bot ({@code /today@other_bot}): those are not ours. */
    public boolean addressedTo(String botUsername) {
        return botMention == null || botMention.equalsIgnoreCase(botUsername);
    }

    public boolean hasArgument() {
        return !argument.isBlank();
    }

    private static int indexOfWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
