package ua.vtc.rozklad.notification.digest;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.binding.ChatBinding;
import ua.vtc.rozklad.notification.binding.ChatBindings;
import ua.vtc.rozklad.notification.bot.ScheduleCards;
import ua.vtc.rozklad.notification.config.BotProperties;
import ua.vtc.rozklad.notification.config.TelegramProperties;
import ua.vtc.rozklad.notification.core.CoreDtos.PublicMeta;
import ua.vtc.rozklad.notification.core.CoreQueries;
import ua.vtc.rozklad.notification.core.CoreQueries.DaySchedule;
import ua.vtc.rozklad.notification.core.OwnerType;
import ua.vtc.rozklad.notification.core.UpstreamRejectedException;
import ua.vtc.rozklad.notification.core.UpstreamUnavailableException;
import ua.vtc.rozklad.notification.delivery.DeliveryPurpose;
import ua.vtc.rozklad.notification.delivery.OutboundDraft;
import ua.vtc.rozklad.notification.delivery.Outbox;
import ua.vtc.rozklad.notification.delivery.Recipient;

/**
 * 07:30 on study days: each subscriber and each group chat with the digest on (group chats opt in with
 * {@code /rozklad_on}) gets today's card of its group. Core
 * decides what a study day is ({@code meta.studyDay}); groups without lessons today get nothing.
 * Idempotent per (date, chat), so a second run or a second instance sends nothing twice.
 */
@Component
public class MorningDigest {

    private static final Logger log = LoggerFactory.getLogger(MorningDigest.class);

    private final ChatBindings bindings;
    private final CoreQueries queries;
    private final Outbox outbox;
    private final BotProperties bot;
    private final TelegramProperties telegram;
    private final Clock clock;

    public MorningDigest(ChatBindings bindings, CoreQueries queries, Outbox outbox, BotProperties bot,
                         TelegramProperties telegram, Clock clock) {
        this.bindings = bindings;
        this.queries = queries;
        this.outbox = outbox;
        this.bot = bot;
        this.telegram = telegram;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.bot.digest-cron:0 30 7 * * MON-FRI}", zone = "Europe/Kyiv")
    public void scheduled() {
        if (bot.digestEnabled() && telegram.enabled()) {
            run(LocalDate.now(clock));
        }
    }

    /** @return number of queued digests */
    public int run(LocalDate date) {
        PublicMeta meta;
        try {
            meta = queries.meta();
        } catch (UpstreamUnavailableException | UpstreamRejectedException e) {
            log.warn("Morning digest skipped: core unavailable ({})", e.getMessage());
            return 0;
        }
        if (!meta.studyDay() || !date.equals(meta.today())) {
            log.info("Morning digest skipped: {} is not a study day", date);
            return 0;
        }
        Map<UUID, List<ChatBinding>> byGroup = bindings.digestSubscribers().stream()
                .collect(Collectors.groupingBy(ChatBinding::getGroupId));
        int queued = 0;
        for (Map.Entry<UUID, List<ChatBinding>> group : byGroup.entrySet()) {
            DaySchedule day;
            try {
                day = queries.day(OwnerType.GROUP, group.getKey(), date);
            } catch (UpstreamUnavailableException | UpstreamRejectedException e) {
                log.warn("Morning digest for group {} skipped: {}", group.getKey(), e.getMessage());
                continue;
            }
            if (day.day().lessons().isEmpty()) {
                continue;
            }
            String text = "☀️ Доброго ранку! Розклад на сьогодні:\n\n" + ScheduleCards.day(day);
            for (ChatBinding subscriber : group.getValue()) {
                queued += outbox.enqueue(OutboundDraft.text(Recipient.of(subscriber), DeliveryPurpose.DIGEST, text)
                        .dedupBy("digest:" + date + ":" + subscriber.getChatId())).size();
            }
        }
        log.info("Morning digest {}: {} message(s) queued for {} group(s)", date, queued, byGroup.size());
        return queued;
    }
}
