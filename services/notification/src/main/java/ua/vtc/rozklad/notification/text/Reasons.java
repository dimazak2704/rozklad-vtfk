package ua.vtc.rozklad.notification.text;

import java.util.Locale;
import java.util.Map;

/**
 * Reasons of changes in plain words for students and teachers. The order document prints the
 * office codes («Відп.», «Зв.зам.», DOMAIN §7); the bot, like the public web
 * ({@code REASON_CODE_HINT} in {@code web/src/lib/labels.ts}), says what they mean. Unknown text
 * (a reason typed by the dispatcher) is shown as it is.
 */
public final class Reasons {

    private static final Locale UK = Locale.forLanguageTag("uk");
    private static final Map<String, String> PUBLIC_WORDING = Map.of(
            "відп.", "викладач відсутній",
            "зв.зам.", "взаємна заміна",
            "вичит.", "години дисципліни вичитано",
            "відр.", "відрядження",
            "інше", "інша причина");

    private Reasons() {
    }

    /** {@code «Зв.зам.»} → {@code «взаємна заміна»} (lower case, for use inside a line). */
    public static String inWords(String reason) {
        if (reason == null) {
            return null;
        }
        String trimmed = reason.strip();
        return PUBLIC_WORDING.getOrDefault(trimmed.toLowerCase(UK), trimmed);
    }
}
