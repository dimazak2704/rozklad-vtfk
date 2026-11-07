package ua.vtc.rozklad.notification.events;

import static ua.vtc.rozklad.notification.text.Html.b;
import static ua.vtc.rozklad.notification.text.Html.escape;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.AbsenceSource;
import ua.vtc.rozklad.contracts.events.AbsenceReported;
import ua.vtc.rozklad.contracts.events.GenerationCompleted;
import ua.vtc.rozklad.notification.binding.ChatBinding;
import ua.vtc.rozklad.notification.binding.ChatBindings;
import ua.vtc.rozklad.notification.delivery.DeliveryPurpose;
import ua.vtc.rozklad.notification.delivery.OutboundDraft;
import ua.vtc.rozklad.notification.delivery.Outbox;
import ua.vtc.rozklad.notification.delivery.Recipient;
import ua.vtc.rozklad.notification.text.UaDates;
import ua.vtc.rozklad.notification.text.WebLinks;

/** Service messages for admin chats: new absences and finished timetable generations. */
@Service
public class AdminNotices {

    private static final int MAX_ERROR = 300;

    private final ChatBindings bindings;
    private final Outbox outbox;
    private final WebLinks links;

    public AdminNotices(ChatBindings bindings, Outbox outbox, WebLinks links) {
        this.bindings = bindings;
        this.outbox = outbox;
        this.links = links;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void onAbsenceReported(AbsenceReported event) {
        String teacher = event.teacherName() == null || event.teacherName().isBlank() ? "Викладач" : event.teacherName();
        String text = "🤒 " + b("Відсутність викладача") + "\n"
                + escape(teacher) + " — " + escape(UaDates.range(event.dateFrom(), event.dateTo()))
                + " (" + pairs(event.pairs()) + ")"
                + "\nДжерело: " + source(event.source())
                + "\nПідберіть заміни." + links.adminChanges(event.dateFrom());
        send(text, DeliveryPurpose.ABSENCE_ADMIN, event.eventId(), "absence");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void onGenerationCompleted(GenerationCompleted event) {
        String text = switch (event.status()) {
            case SOLVED -> "🧩 " + b("Генерацію розкладу завершено")
                    + "\nЖорсткі порушення: " + Math.abs(event.hardScore())
                    + " · штраф м’яких обмежень: " + Math.abs(event.softScore())
                    + topPenalties(event.analysis())
                    + "\nПерегляньте результат і застосуйте як чернетку." + links.adminGeneration();
            case FAILED -> "⚠️ " + b("Генерація розкладу не вдалася")
                    + (event.error() == null || event.error().isBlank() ? "" : "\n" + escape(truncate(event.error())))
                    + links.adminGeneration();
            case CANCELLED -> "⏹ " + b("Генерацію розкладу скасовано.") + links.adminGeneration();
            case QUEUED, RUNNING -> null;
        };
        if (text != null) {
            send(text, DeliveryPurpose.GENERATION_ADMIN, event.eventId(), "generation");
        }
    }

    private void send(String text, DeliveryPurpose purpose, UUID eventId, String part) {
        for (ChatBinding admin : bindings.adminChats()) {
            outbox.enqueue(OutboundDraft.text(Recipient.of(admin), purpose, text).forEvent(eventId, part));
        }
    }

    static String pairs(List<Integer> pairs) {
        if (pairs == null || pairs.isEmpty()) {
            return "увесь день";
        }
        List<Integer> sorted = pairs.stream().sorted().toList();
        return sorted.size() == 1
                ? sorted.getFirst() + " пара"
                : "пари " + sorted.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    static String source(AbsenceSource source) {
        return switch (source) {
            case WEB -> "кабінет викладача";
            case BOT -> "Telegram-бот";
            case ADMIN -> "диспетчер";
        };
    }

    private static String topPenalties(List<GenerationCompleted.ConstraintScore> analysis) {
        List<GenerationCompleted.ConstraintScore> top = analysis.stream()
                .filter(score -> score.matchCount() > 0)
                .sorted(Comparator.comparingInt(GenerationCompleted.ConstraintScore::matchCount).reversed())
                .limit(3)
                .toList();
        if (top.isEmpty()) {
            return "";
        }
        return "\nНайбільше порушень:\n" + top.stream()
                .map(score -> "• " + escape(score.constraint()) + " — " + score.matchCount())
                .collect(Collectors.joining("\n"));
    }

    private static String truncate(String text) {
        return text.length() <= MAX_ERROR ? text : text.substring(0, MAX_ERROR - 1) + "…";
    }
}
