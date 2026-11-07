package ua.vtc.rozklad.notification.delivery;

public enum OutboundKind {
    /** New HTML message, optionally with inline buttons. */
    TEXT,
    /** Picture (media reference resolved at send time) with an HTML caption. */
    PHOTO,
    /** New text/buttons for an already sent message. */
    EDIT_TEXT
}
