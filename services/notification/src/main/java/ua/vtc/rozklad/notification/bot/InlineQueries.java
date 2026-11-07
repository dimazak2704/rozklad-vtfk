package ua.vtc.rozklad.notification.bot;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.bot.BotUpdate.InlineRequest;
import ua.vtc.rozklad.notification.core.CoreDtos.SearchHit;
import ua.vtc.rozklad.notification.core.CoreQueries;
import ua.vtc.rozklad.notification.core.CoreQueries.DaySchedule;
import ua.vtc.rozklad.notification.core.UpstreamRejectedException;
import ua.vtc.rozklad.notification.core.UpstreamUnavailableException;
import ua.vtc.rozklad.notification.telegram.TelegramApi.InlineArticle;
import ua.vtc.rozklad.notification.text.UaDates;

/**
 * Inline mode: {@code @scheduler_vtfk_bot 4КІ1} in any chat offers the card of the nearest study
 * day and the next one, ready to be sent to friends.
 */
@Component
public class InlineQueries {

    static final int MAX_OWNERS = 2;
    static final int MAX_QUERY = 50;

    private static final Logger log = LoggerFactory.getLogger(InlineQueries.class);

    private final ChatAccess access;
    private final CoreQueries queries;
    private final BotReplies replies;
    private final Clock clock;

    public InlineQueries(ChatAccess access, CoreQueries queries, BotReplies replies, Clock clock) {
        this.access = access;
        this.queries = queries;
        this.replies = replies;
        this.clock = clock;
    }

    public void onQuery(InlineRequest request) {
        String query = request.query().strip();
        // приватний чат користувача має той самий id, що й користувач
        if (!access.mayTalkTo(request.fromUserId()) || query.isEmpty() || query.length() > MAX_QUERY) {
            replies.answerInline(request.queryId(), List.of());
            return;
        }
        List<InlineArticle> articles = new ArrayList<>();
        try {
            LocalDate today = LocalDate.now(clock);
            LocalDate first = StudyDays.onOrAfter(today);
            List<LocalDate> dates = List.of(first, StudyDays.next(first));
            List<SearchHit> hits = queries.search(query).stream()
                    .filter(hit -> hit.ownerType() != null && hit.id() != null)
                    .limit(MAX_OWNERS)
                    .toList();
            for (SearchHit hit : hits) {
                for (LocalDate date : dates) {
                    DaySchedule day = queries.day(hit.ownerType(), hit.id(), date);
                    articles.add(new InlineArticle(articleId(hit, date), title(hit, day, today),
                            ScheduleCards.summary(day.day()), ScheduleCards.day(day)));
                }
            }
        } catch (UpstreamUnavailableException | UpstreamRejectedException e) {
            log.warn("Inline query without results: core unavailable ({})", e.getMessage());
        }
        replies.answerInline(request.queryId(), articles);
    }

    private static String articleId(SearchHit hit, LocalDate date) {
        return hit.ownerType().code() + hit.id().toString().replace("-", "")
                + date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private static String title(SearchHit hit, DaySchedule day, LocalDate today) {
        LocalDate date = day.day().date();
        String when = UaDates.weekdayShort(date) + " " + UaDates.shortDate(date);
        if (date.equals(today)) {
            when = "сьогодні, " + when;
        } else if (date.equals(today.plusDays(1))) {
            when = "завтра, " + when;
        }
        String label = day.ownerLabel() == null || day.ownerLabel().isBlank() ? hit.label() : day.ownerLabel();
        return label + " — " + when;
    }
}
