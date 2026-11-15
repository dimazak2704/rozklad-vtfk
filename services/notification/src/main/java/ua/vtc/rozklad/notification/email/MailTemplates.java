package ua.vtc.rozklad.notification.email;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Lays out a {@link MailContent} with {@code mail/letter.html} (every value escaped by {@code th:text}) and
 * {@code mail/letter.txt}; the text part is tidied (no trailing spaces, at most one blank line in a row).
 */
@Component
public class MailTemplates {

    private static final Locale UK = Locale.forLanguageTag("uk");
    private static final Pattern TRAILING_SPACES = Pattern.compile("[ \\t]+(?=\\n)");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

    private final TemplateEngine engine;

    public MailTemplates(@Qualifier("mailTemplateEngine") TemplateEngine engine) {
        this.engine = engine;
    }

    public Rendered render(MailContent content) {
        Context context = new Context(UK);
        context.setVariable("mail", content);
        String html = engine.process("letter.html", context);
        String text = engine.process("letter.txt", context).replace("\r\n", "\n");
        text = BLANK_LINES.matcher(TRAILING_SPACES.matcher(text).replaceAll("")).replaceAll("\n\n").strip() + "\n";
        return new Rendered(MailHeaders.clean(content.subject(), 250), html, text);
    }

    /** The subject as it goes into the header, and both alternatives of the body. */
    public record Rendered(String subject, String html, String text) {
    }
}
