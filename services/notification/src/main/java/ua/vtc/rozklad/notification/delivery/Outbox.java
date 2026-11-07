package ua.vtc.rozklad.notification.delivery;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.notification.shared.ChatIds;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineKeyboard;
import ua.vtc.rozklad.notification.text.Html;

/**
 * Puts push messages into the persistent queue — in the caller's transaction, so an event is
 * marked processed together with its messages. Long texts are split; the keyboard goes to the
 * last part. A draft whose {@code dedupKey} was queued before is skipped (idempotency).
 */
@Service
public class Outbox {

    private static final Logger log = LoggerFactory.getLogger(Outbox.class);

    private final OutboundMessageRepository repository;
    private final Clock clock;

    public Outbox(OutboundMessageRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** @return the queued messages (empty when the draft was a duplicate) */
    @Transactional
    public List<OutboundMessage> enqueue(OutboundDraft draft) {
        Instant now = clock.instant();
        List<String> texts = switch (draft.kind()) {
            case TEXT -> Html.split(draft.text(), Html.MAX_MESSAGE);
            case PHOTO -> Collections.singletonList(draft.text() == null ? null
                    : truncate(draft.text(), Html.MAX_CAPTION));
            case EDIT_TEXT -> List.of(truncate(draft.text(), Html.MAX_MESSAGE));
        };
        List<OutboundMessage> queued = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            boolean last = i == texts.size() - 1;
            String dedupKey = draft.dedupKey() == null ? null
                    : texts.size() == 1 ? draft.dedupKey() : draft.dedupKey() + ":" + (i + 1);
            if (dedupKey != null && repository.existsByDedupKey(dedupKey)) {
                log.debug("Skipping duplicate message {}", dedupKey);
                continue;
            }
            InlineKeyboard keyboard = last ? draft.keyboard() : null;
            queued.add(repository.save(new OutboundMessage(draft, texts.get(i), keyboard, dedupKey, now)));
        }
        if (!queued.isEmpty()) {
            log.debug("Queued {} {} message(s) for chat {}", queued.size(), draft.purpose(),
                    ChatIds.mask(draft.recipient().chatId()));
        }
        return queued;
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
