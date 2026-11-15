package ua.vtc.rozklad.notification.email;

import java.util.List;

/**
 * A letter's content before layout: plain strings only (the HTML template escapes every one of them, the text
 * template prints them as they are). The same structure fits every kind, so one HTML and one text template
 * render all letters.
 *
 * @param preheader  the hidden first line mail clients show next to the subject
 * @param greeting   «Добрий день!» or null
 * @param obsolete   the details are no longer valid (a cancelled change): muted, marked «більше не актуально»
 * @param itemsTitle the line right above the list («Найбільше порушень:»), or null
 * @param items      a bulleted list (top penalties, order rows, pairs), may be empty
 * @param button     the main link, or null
 * @param links      further links shown as text
 * @param settingsUrl the settings page in the footer and in {@code List-Unsubscribe}
 */
public record MailContent(String subject, String preheader, String title, String greeting, List<String> intro,
                          List<Detail> details, boolean obsolete, String itemsTitle, List<String> items,
                          List<String> outro, Link button,
                          List<Link> links, String settingsUrl) {

    public MailContent {
        intro = intro == null ? List.of() : List.copyOf(intro);
        details = details == null ? List.of() : List.copyOf(details);
        items = items == null ? List.of() : List.copyOf(items);
        outro = outro == null ? List.of() : List.copyOf(outro);
        links = links == null ? List.of() : List.copyOf(links);
    }

    public record Detail(String label, String value) {
    }

    public record Link(String label, String url) {
    }
}
