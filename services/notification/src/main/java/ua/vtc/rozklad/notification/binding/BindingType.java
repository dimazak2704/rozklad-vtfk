package ua.vtc.rozklad.notification.binding;

import ua.vtc.rozklad.notification.bot.BotUpdate.ChatKind;

/** What a Telegram chat is bound to (CONTRACTS §6). */
public enum BindingType {
    /** Chat of a student group: receives its order rows and the order picture. */
    GROUP_CHAT,
    /** Private chat of a teacher: substitution requests, own order rows, absence reports. */
    TEACHER,
    /** Dispatcher chat: absences, declined/expired confirmations, order summaries. */
    ADMIN_CHAT,
    /** Private chat of a student/parent following one group (today/tomorrow, morning digest). */
    SUBSCRIBER;

    /** Where a code of this type may be redeemed. */
    public boolean fitsChat(ChatKind kind) {
        return switch (this) {
            case GROUP_CHAT -> kind == ChatKind.GROUP;
            case TEACHER, SUBSCRIBER -> kind == ChatKind.PRIVATE;
            case ADMIN_CHAT -> kind == ChatKind.PRIVATE || kind == ChatKind.GROUP;
        };
    }

    public boolean needsGroup() {
        return this == GROUP_CHAT || this == SUBSCRIBER;
    }

    /** Chats that can get the 07:30 schedule of their group: a subscriber (on by default) and a group chat (opt-in). */
    public boolean canGetDigest() {
        return needsGroup();
    }
}
