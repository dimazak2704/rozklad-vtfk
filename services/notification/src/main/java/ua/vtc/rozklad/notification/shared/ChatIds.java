package ua.vtc.rozklad.notification.shared;

/** Telegram chat ids: masking for logs/API and the private/group distinction. */
public final class ChatIds {

    private ChatIds() {
    }

    /** {@code -1001234567890 → -•••7890}: enough to tell chats apart, useless to anyone else. */
    public static String mask(long chatId) {
        String digits = Long.toString(Math.abs(chatId));
        String tail = digits.length() <= 4 ? digits : digits.substring(digits.length() - 4);
        return (chatId < 0 ? "-" : "") + "•••" + tail;
    }

    /** Private chats (users) have positive ids, groups and supergroups negative ones. */
    public static boolean isPrivate(long chatId) {
        return chatId > 0;
    }
}
