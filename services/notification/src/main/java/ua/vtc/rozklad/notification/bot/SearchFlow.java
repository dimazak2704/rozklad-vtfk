package ua.vtc.rozklad.notification.bot;

import static ua.vtc.rozklad.notification.text.Html.code;
import static ua.vtc.rozklad.notification.text.Html.escape;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.core.CoreDtos.SearchHit;
import ua.vtc.rozklad.notification.core.CoreQueries;
import ua.vtc.rozklad.notification.core.OwnerType;
import ua.vtc.rozklad.notification.core.UpstreamRejectedException;
import ua.vtc.rozklad.notification.core.UpstreamUnavailableException;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineButton;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineKeyboard;

/** «Знайти»: a group, a teacher or a room by any spelling (core's fuzzy search) → today's card. */
@Component
public class SearchFlow {

    static final int MAX_QUERY = 50;

    private final CoreQueries queries;
    private final DialogStore dialogs;
    private final BotReplies replies;
    private final ScheduleViews views;
    private final Clock clock;

    public SearchFlow(CoreQueries queries, DialogStore dialogs, BotReplies replies, ScheduleViews views, Clock clock) {
        this.queries = queries;
        this.dialogs = dialogs;
        this.replies = replies;
        this.views = views;
        this.clock = clock;
    }

    public void start(long chatId) {
        dialogs.save(chatId, Dialog.search());
        replies.send(chatId, "🔎 Кого шукаємо? Напишіть групу, прізвище викладача або аудиторію.\n"
                + "Наприклад: " + code("4КІ1") + ", " + code("Кренцін") + ", " + code("204"), null);
    }

    public void query(long chatId, String text) {
        String query = text.strip();
        if (query.isEmpty() || query.length() > MAX_QUERY) {
            replies.send(chatId, "Напишіть коротко: група, прізвище або номер аудиторії.", null);
            return;
        }
        List<SearchHit> hits;
        try {
            hits = queries.search(query).stream().filter(hit -> hit.ownerType() != null && hit.id() != null).toList();
        } catch (UpstreamUnavailableException | UpstreamRejectedException e) {
            replies.send(chatId, BotTexts.CORE_DOWN, null);
            return;
        }
        if (hits.isEmpty()) {
            dialogs.save(chatId, Dialog.search());
            replies.send(chatId, "Нічого не знайшов за «" + escape(query) + "» 🤷\n"
                    + "Спробуйте інакше: " + code("4КІ1") + ", " + code("Кренцін") + ", " + code("204") + ".", null);
            return;
        }
        dialogs.clear(chatId);
        if (hits.size() == 1) {
            SearchHit hit = hits.getFirst();
            views.showDay(chatId, null, hit.ownerType(), hit.id(), LocalDate.now(clock), true);
            return;
        }
        List<InlineButton> buttons = hits.stream()
                .map(hit -> InlineButton.callback(buttonText(hit), CallbackData.pick(hit.ownerType(), hit.id())))
                .toList();
        replies.send(chatId, "Знайшов кілька варіантів — оберіть:", InlineKeyboard.grid(buttons, 1));
    }

    /** A result button: today's card in place of the list. */
    public void pick(long chatId, long messageId, OwnerType type, UUID ownerId) {
        views.showDay(chatId, messageId, type, ownerId, LocalDate.now(clock), true);
    }

    private static String buttonText(SearchHit hit) {
        String icon = switch (hit.ownerType()) {
            case GROUP -> "👥 ";
            case TEACHER -> "🧑‍🏫 ";
            case ROOM -> "🚪 ";
        };
        String text = icon + hit.label() + (hit.sublabel() == null || hit.sublabel().isBlank() ? ""
                : " · " + hit.sublabel());
        return text.length() > 60 ? text.substring(0, 59) + "…" : text;
    }
}
