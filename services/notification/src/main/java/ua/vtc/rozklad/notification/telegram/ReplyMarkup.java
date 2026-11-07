package ua.vtc.rozklad.notification.telegram;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Keyboards attached to a message, independent of the Telegram library types. */
public sealed interface ReplyMarkup permits ReplyMarkup.InlineKeyboard, ReplyMarkup.ReplyKeyboard,
        ReplyMarkup.RemoveKeyboard {

    /** Buttons under a message; pressing one sends a callback query (or opens a link). */
    record InlineKeyboard(List<List<InlineButton>> rows) implements ReplyMarkup {

        public InlineKeyboard {
            Objects.requireNonNull(rows, "rows");
            rows = rows.stream().filter(row -> !row.isEmpty()).map(List::copyOf).toList();
        }

        public static InlineKeyboard of(List<List<InlineButton>> rows) {
            return new InlineKeyboard(rows);
        }

        @SafeVarargs
        public static InlineKeyboard rows(List<InlineButton>... rows) {
            return new InlineKeyboard(List.of(rows));
        }

        /** Lays buttons out {@code perRow} in a row. */
        public static InlineKeyboard grid(List<InlineButton> buttons, int perRow) {
            List<List<InlineButton>> rows = new ArrayList<>();
            for (int i = 0; i < buttons.size(); i += perRow) {
                rows.add(buttons.subList(i, Math.min(i + perRow, buttons.size())));
            }
            return new InlineKeyboard(rows);
        }

        public InlineKeyboard plusRow(List<InlineButton> row) {
            List<List<InlineButton>> all = new ArrayList<>(rows);
            all.add(row);
            return new InlineKeyboard(all);
        }

        @JsonIgnore
        public boolean isEmpty() {
            return rows.isEmpty();
        }
    }

    /**
     * One inline button: exactly one of callback data, URL or inline query is set. Callback data is
     * limited by Telegram to 64 bytes.
     */
    record InlineButton(String text, String callbackData, String url, String switchInlineQueryCurrentChat) {

        public static final int MAX_CALLBACK_BYTES = 64;

        public InlineButton {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("button text is empty");
            }
            int actions = (callbackData != null ? 1 : 0) + (url != null ? 1 : 0)
                    + (switchInlineQueryCurrentChat != null ? 1 : 0);
            if (actions != 1) {
                throw new IllegalArgumentException("exactly one button action expected: " + text);
            }
            if (callbackData != null && callbackData.getBytes(StandardCharsets.UTF_8).length > MAX_CALLBACK_BYTES) {
                throw new IllegalArgumentException("callback data longer than 64 bytes: " + callbackData);
            }
        }

        public static InlineButton callback(String text, String data) {
            return new InlineButton(text, data, null, null);
        }

        public static InlineButton link(String text, String url) {
            return new InlineButton(text, null, url, null);
        }

        public static InlineButton inlineQuery(String text, String query) {
            return new InlineButton(text, null, null, query);
        }
    }

    /** Persistent keyboard under the input field; a pressed button sends its text as a message. */
    record ReplyKeyboard(List<List<String>> rows, String placeholder) implements ReplyMarkup {

        public ReplyKeyboard {
            Objects.requireNonNull(rows, "rows");
            rows = rows.stream().map(List::copyOf).toList();
        }
    }

    /** Hides a previously shown reply keyboard. */
    record RemoveKeyboard() implements ReplyMarkup {
    }
}
