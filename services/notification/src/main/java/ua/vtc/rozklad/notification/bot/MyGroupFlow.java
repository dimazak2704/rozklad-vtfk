package ua.vtc.rozklad.notification.bot;

import static ua.vtc.rozklad.notification.text.Html.b;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.binding.ChatBinding;
import ua.vtc.rozklad.notification.binding.ChatBindings;
import ua.vtc.rozklad.notification.core.CoreDtos.PublicGroup;
import ua.vtc.rozklad.notification.core.CoreQueries;
import ua.vtc.rozklad.notification.core.UpstreamRejectedException;
import ua.vtc.rozklad.notification.core.UpstreamUnavailableException;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineButton;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineKeyboard;

/** «Моя група»: course → group → subscription (SUBSCRIBER binding) and the morning digest switch. */
@Component
public class MyGroupFlow {

    private final CoreQueries queries;
    private final ChatBindings bindings;
    private final BotReplies replies;

    public MyGroupFlow(CoreQueries queries, ChatBindings bindings, BotReplies replies) {
        this.queries = queries;
        this.bindings = bindings;
        this.replies = replies;
    }

    /** Menu button: current subscription with actions, or the course picker. */
    public void open(long chatId) {
        Optional<ChatBinding> subscription = bindings.subscription(chatId);
        if (subscription.isEmpty()) {
            showCourses(chatId, null);
            return;
        }
        replies.send(chatId, subscriptionText(subscription.get()), subscriptionKeyboard(subscription.get()));
    }

    public void showCourses(long chatId, Long editMessageId) {
        List<PublicGroup> groups;
        try {
            groups = queries.groups();
        } catch (UpstreamUnavailableException | UpstreamRejectedException e) {
            show(chatId, editMessageId, BotTexts.CORE_DOWN, null);
            return;
        }
        List<Integer> courses = groups.stream().map(PublicGroup::course).distinct().sorted().toList();
        if (courses.isEmpty()) {
            show(chatId, editMessageId, "Список груп поки порожній. Спробуйте пізніше.", null);
            return;
        }
        List<InlineButton> buttons = courses.stream()
                .map(course -> InlineButton.callback(course + " курс", CallbackData.course(course)))
                .toList();
        show(chatId, editMessageId, "👥 Оберіть курс:", InlineKeyboard.grid(buttons, 4));
    }

    public void showCourse(long chatId, long messageId, int course) {
        List<PublicGroup> groups;
        try {
            groups = queries.groups().stream().filter(group -> group.course() == course).toList();
        } catch (UpstreamUnavailableException | UpstreamRejectedException e) {
            replies.edit(chatId, messageId, BotTexts.CORE_DOWN, null);
            return;
        }
        List<InlineButton> buttons = groups.stream()
                .map(group -> InlineButton.callback(group.code(), CallbackData.group(group.id())))
                .toList();
        InlineKeyboard keyboard = InlineKeyboard.grid(buttons, 4)
                .plusRow(List.of(InlineButton.callback("⬅️ Курси", CallbackData.COURSES)));
        replies.edit(chatId, messageId, b(course + " курс") + " — оберіть групу:", keyboard);
    }

    /** A group button in the picker. */
    public void choose(String callbackId, long chatId, long messageId, UUID groupId) {
        Optional<PublicGroup> group = findGroup(callbackId, groupId);
        if (group.isEmpty()) {
            return;
        }
        ChatBinding subscription = bindings.subscribe(chatId, group.get());
        replies.edit(chatId, messageId, "✅ Ваша група — " + b(group.get().code()) + ".\n"
                + "«📅 Сьогодні» і «📆 Завтра» тепер показують її розклад"
                + (subscription.isDigestEnabled()
                ? ", а щоранку о 7:30 у навчальні дні я надсилатиму розклад на день."
                : "."), subscriptionKeyboard(subscription));
        replies.answer(callbackId, "Групу збережено");
    }

    /** «⭐ Зробити моєю групою» under a day card. */
    public void subscribeFromCard(String callbackId, long chatId, UUID groupId) {
        Optional<PublicGroup> group = findGroup(callbackId, groupId);
        if (group.isEmpty()) {
            return;
        }
        bindings.subscribe(chatId, group.get());
        replies.answer(callbackId, "Готово! " + group.get().code() + " — ваша група");
    }

    public void setDigest(String callbackId, long chatId, long messageId, boolean enabled) {
        if (!bindings.setDigest(chatId, enabled)) {
            replies.alert(callbackId, "Спершу оберіть свою групу.");
            return;
        }
        ChatBinding subscription = bindings.subscription(chatId).orElseThrow();
        replies.edit(chatId, messageId, subscriptionText(subscription), subscriptionKeyboard(subscription));
        replies.answer(callbackId, enabled ? "Ранкову розсилку увімкнено" : "Ранкову розсилку вимкнено");
    }

    private Optional<PublicGroup> findGroup(String callbackId, UUID groupId) {
        try {
            Optional<PublicGroup> group = queries.group(groupId);
            if (group.isEmpty()) {
                replies.alert(callbackId, "Групу не знайдено — можливо, список змінився. Відкрийте «Моя група» ще раз.");
            }
            return group;
        } catch (UpstreamUnavailableException | UpstreamRejectedException e) {
            replies.alert(callbackId, "Сервер розкладу зараз недоступний. Спробуйте за хвилину.");
            return Optional.empty();
        }
    }

    private static String subscriptionText(ChatBinding subscription) {
        return "👥 Ваша група: " + b(subscription.getGroupCode()) + "\n"
                + (subscription.isDigestEnabled()
                ? "🔔 Ранковий розклад о 7:30 у навчальні дні: увімкнено"
                : "🔕 Ранковий розклад о 7:30: вимкнено");
    }

    private static InlineKeyboard subscriptionKeyboard(ChatBinding subscription) {
        InlineButton digest = subscription.isDigestEnabled()
                ? InlineButton.callback("🔕 Вимкнути ранкову розсилку", CallbackData.DIGEST_OFF)
                : InlineButton.callback("🔔 Увімкнути ранкову розсилку", CallbackData.DIGEST_ON);
        return InlineKeyboard.rows(List.of(digest),
                List.of(InlineButton.callback("🔁 Змінити групу", CallbackData.COURSES)));
    }

    private void show(long chatId, Long editMessageId, String text, InlineKeyboard keyboard) {
        if (editMessageId != null) {
            replies.edit(chatId, editMessageId, text, keyboard);
        } else {
            replies.send(chatId, text, keyboard);
        }
    }
}
