package ua.vtc.rozklad.notification.email;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.notification.config.MailChannelProperties;

/** Retention of the e-mail log: finished letters and intents are deleted after {@code app.mail.retention}. */
@Component
public class EmailHousekeeping {

    private final EmailMessageRepository messages;
    private final EmailNoticeRepository notices;
    private final MailChannelProperties properties;

    public EmailHousekeeping(EmailMessageRepository messages, EmailNoticeRepository notices,
                             MailChannelProperties properties) {
        this.messages = messages;
        this.notices = notices;
        this.properties = properties;
    }

    /** @param now real time (the rows are stamped in real time) @return letters and intents removed */
    @Transactional
    public int purge(Instant now) {
        Instant before = now.minus(properties.retention());
        int letters = messages.deleteFinishedBefore(List.of(EmailMessageStatus.SENT, EmailMessageStatus.FAILED,
                EmailMessageStatus.SUPERSEDED), before);
        int intents = notices.deleteFinishedBefore(List.of(EmailNoticeStatus.EXPANDED, EmailNoticeStatus.FAILED),
                before);
        return letters + intents;
    }
}
