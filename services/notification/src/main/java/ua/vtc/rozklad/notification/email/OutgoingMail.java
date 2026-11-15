package ua.vtc.rozklad.notification.email;

import java.util.Objects;

/**
 * One letter as the transport sends it: one recipient, a subject, the HTML and plain-text alternatives and the
 * headers that keep the letters of one change together.
 *
 * @param messageId       {@code Message-ID} without angle brackets ({@code {letterId}@{domain}})
 * @param threadId        {@code References}/{@code In-Reply-To} without angle brackets, or null
 * @param unsubscribeUrl  {@code List-Unsubscribe}: the signed-in settings page (no token in the link)
 */
public record OutgoingMail(String to, String toName, String subject, String html, String text, String messageId,
                           String threadId, String unsubscribeUrl) {

    public OutgoingMail {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(html, "html");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(messageId, "messageId");
    }
}
