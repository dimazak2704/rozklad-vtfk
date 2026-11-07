package ua.vtc.rozklad.notification.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.notification.binding.BindingTarget;
import ua.vtc.rozklad.notification.binding.BindingType;
import ua.vtc.rozklad.notification.binding.ChatBinding;
import ua.vtc.rozklad.notification.bot.BotUpdate.InlineRequest;
import ua.vtc.rozklad.notification.support.CoreFixtures;
import ua.vtc.rozklad.notification.support.FakeTelegramClient.Edit;
import ua.vtc.rozklad.notification.support.FakeTelegramClient.Sent;
import ua.vtc.rozklad.notification.support.IntegrationTest;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.ReplyKeyboard;

/** Private chat flows of UI.md §5 through the dispatcher, checked on what Telegram would receive. */
class PrivateChatFlowsIT extends IntegrationTest {

    private static final long STUDENT = 3001;
    private static final long TEACHER = 2001;
    private static final long STRANGER = 9999;

    @Test
    void strangersAreIgnoredWhileTheBotIsBoundOnly() {
        say(STRANGER, "/start");
        say(STRANGER, "📅 Сьогодні");
        say(STRANGER, "4КІ1");
        assertThat(telegram.sentTo(STRANGER)).isEmpty();
    }

    @Test
    void ownerIsGreetedWithTheMainKeyboard() {
        owner();
        say(OWNER_CHAT, "/start");
        Sent greeting = telegram.lastSentTo(OWNER_CHAT);
        assertThat(greeting.text()).contains("Я бот розкладу ВТФК").contains("«👥 Моя група»");
        assertThat(greeting.markup()).isInstanceOfSatisfying(ReplyKeyboard.class, keyboard -> assertThat(keyboard.rows())
                .containsExactly(List.of("📅 Сьогодні", "📆 Завтра"), List.of("👥 Моя група", "🔎 Знайти"),
                        List.of("🔔 Дзвінки", "🔢 Чисельник?")));
    }

    @Test
    void deepLinkBindsTheTeacherAndCannotBeReused() {
        String code = issue(BindingTarget.teacher(CoreFixtures.TEACHER_NEPYIVODA, "Непийвода М.В."));
        say(TEACHER, "/start " + code);

        Sent welcome = telegram.lastSentTo(TEACHER);
        assertThat(welcome.text()).startsWith("✅ Вітаю, <b>Непийвода М.В.</b>!").contains("прохання про заміни");
        assertThat(((ReplyKeyboard) welcome.markup()).rows()).contains(List.of("🧑‍🏫 Мої пари завтра", "🤒 Відсутність"));
        assertThat(bindings.teacherBinding(TEACHER)).get().extracting(ChatBinding::getTeacherId)
                .isEqualTo(CoreFixtures.TEACHER_NEPYIVODA);

        // той самий код у чужому чаті: код справжній, тож бот пояснює, що він уже використаний
        say(STRANGER, "/start " + code);
        assertThat(telegram.lastSentTo(STRANGER).text()).contains("уже використано");
        assertThat(bindings.isKnownChat(STRANGER)).isFalse();

        // вигаданий код у невідомому чаті — тиша
        say(STRANGER + 1, "/start ABCDEFGH");
        assertThat(telegram.sentTo(STRANGER + 1)).isEmpty();
    }

    @Test
    void expiredCodeIsExplained() {
        String code = issue(BindingTarget.group(BindingType.SUBSCRIBER, CoreFixtures.GROUP_4KI1, "4КІ1"));
        clock.advance(java.time.Duration.ofHours(25));
        say(STUDENT, "/start " + code);
        assertThat(telegram.lastSentTo(STUDENT).text()).contains("Термін дії коду минув");
        assertThat(bindings.subscription(STUDENT)).isEmpty();
    }

    @Test
    void myGroupGoesCourseGroupSubscriptionAndTogglesTheDigest() {
        subscriberCodeFor(STUDENT);
        bindings.forgetChat(STUDENT);
        owner();

        say(OWNER_CHAT, "👥 Моя група");
        Sent courses = telegram.lastSentTo(OWNER_CHAT);
        assertThat(courses.text()).isEqualTo("👥 Оберіть курс:");
        assertThat(courses.buttons()).containsExactly("1 курс", "2 курс", "4 курс");

        press(OWNER_CHAT, courses.messageId(), courses.callbackOf("4 курс").orElseThrow());
        Edit groups = telegram.lastEditIn(OWNER_CHAT);
        assertThat(groups.text()).isEqualTo("<b>4 курс</b> — оберіть групу:");
        assertThat(groups.buttons()).containsExactly("4КІ1", "4КІ2", "⬅️ Курси");

        press(OWNER_CHAT, courses.messageId(), groups.callbackOf("4КІ1").orElseThrow());
        Edit chosen = telegram.lastEditIn(OWNER_CHAT);
        assertThat(chosen.text()).startsWith("✅ Ваша група — <b>4КІ1</b>.").contains("щоранку о 7:30");
        assertThat(chosen.buttons()).containsExactly("🔕 Вимкнути ранкову розсилку", "🔁 Змінити групу");
        assertThat(telegram.lastCallbackAnswer().text()).isEqualTo("Групу збережено");
        assertThat(bindings.subscription(OWNER_CHAT)).get().satisfies(subscription -> {
            assertThat(subscription.getGroupCode()).isEqualTo("4КІ1");
            assertThat(subscription.isDigestEnabled()).isTrue();
        });

        press(OWNER_CHAT, courses.messageId(), chosen.callbackOf("🔕 Вимкнути ранкову розсилку").orElseThrow());
        assertThat(telegram.lastEditIn(OWNER_CHAT).text()).contains("🔕 Ранковий розклад о 7:30: вимкнено");
        assertThat(bindings.subscription(OWNER_CHAT).orElseThrow().isDigestEnabled()).isFalse();

        say(OWNER_CHAT, "👥 Моя група");
        assertThat(telegram.lastSentTo(OWNER_CHAT).text()).startsWith("👥 Ваша група: <b>4КІ1</b>");
    }

    @Test
    void todayAndTomorrowShowTheSubscribedGroupWithNavigation() {
        owner();
        say(OWNER_CHAT, "📅 Сьогодні");
        assertThat(telegram.sentTo(OWNER_CHAT)).extracting(Sent::text)
                .containsExactly(BotTexts.CHOOSE_GROUP_FIRST, "👥 Оберіть курс:");

        subscriber(OWNER_CHAT, CoreFixtures.GROUP_4KI1, "4КІ1");
        say(OWNER_CHAT, "📅 Сьогодні");
        Sent today = telegram.lastSentTo(OWNER_CHAT);
        assertThat(today.text()).startsWith("👥 <b>4КІ1</b> · понеділок, 19 жовтня\n<i>Знаменник</i>")
                .contains("<b>2</b> · 09:40–10:40 · 🔁 заміна\nНДКС · Непийвода М.В. · ауд. 6")
                .contains("замість <s>СТП · Кренцін М.Д.</s>");
        assertThat(today.buttons()).containsExactly("◀️ Пт 16.10", "Вт 20.10 ▶️");

        press(OWNER_CHAT, today.messageId(), today.callbackOf("Вт 20.10 ▶️").orElseThrow());
        Edit tuesday = telegram.lastEditIn(OWNER_CHAT);
        assertThat(tuesday.messageId()).isEqualTo(today.messageId());
        assertThat(tuesday.text()).startsWith("👥 <b>4КІ1</b> · вівторок, 20 жовтня");

        say(OWNER_CHAT, "/tomorrow");
        assertThat(telegram.lastSentTo(OWNER_CHAT).text()).startsWith("👥 <b>4КІ1</b> · вівторок, 20 жовтня");
    }

    @Test
    void searchFindsGroupsTeachersAndRoomsAndOffersToFollowAGroup() {
        owner();
        say(OWNER_CHAT, "🔎 Знайти");
        assertThat(telegram.lastSentTo(OWNER_CHAT).text()).startsWith("🔎 Кого шукаємо?");

        say(OWNER_CHAT, "4кі");
        Sent results = telegram.lastSentTo(OWNER_CHAT);
        assertThat(results.text()).isEqualTo("Знайшов кілька варіантів — оберіть:");
        assertThat(results.buttons()).containsExactly("👥 4КІ1 · Комп’ютерна інженерія", "👥 4КІ2 · Комп’ютерна інженерія");

        press(OWNER_CHAT, results.messageId(), results.callbackOf("👥 4КІ1 · Комп’ютерна інженерія").orElseThrow());
        Edit card = telegram.lastEditIn(OWNER_CHAT);
        assertThat(card.text()).startsWith("👥 <b>4КІ1</b> · понеділок, 19 жовтня");
        assertThat(card.buttons()).contains("⭐ Зробити моєю групою");

        press(OWNER_CHAT, card.messageId(), card.callbackOf("⭐ Зробити моєю групою").orElseThrow());
        assertThat(telegram.lastCallbackAnswer().text()).isEqualTo("Готово! 4КІ1 — ваша група");
        assertThat(bindings.subscription(OWNER_CHAT)).isPresent();

        // вільний текст без діалогу — теж пошук
        say(OWNER_CHAT, "кренц");
        assertThat(telegram.lastSentTo(OWNER_CHAT).text()).startsWith("🧑‍🏫 <b>Кренцін М.Д.</b> · понеділок, 19 жовтня");

        say(OWNER_CHAT, "абракадабра");
        assertThat(telegram.lastSentTo(OWNER_CHAT).text()).startsWith("Нічого не знайшов за «абракадабра»");
    }

    @Test
    void bellsAndParity() {
        owner();
        say(OWNER_CHAT, "🔔 Дзвінки");
        assertThat(telegram.lastSentTo(OWNER_CHAT).text())
                .startsWith("🔔 <b>Дзвінки</b> · понеділок\nЗараз <b>2 пара</b> — до кінця 23 хв")
                .contains("<pre>1  08:30–09:30\n2  09:40–10:40  ◀ зараз\n3  11:00–12:00\n4  12:10–13:10</pre>")
                .endsWith("<i>2 зміни, 60 хв</i>");

        say(OWNER_CHAT, "🔢 Чисельник?");
        assertThat(telegram.lastSentTo(OWNER_CHAT).text())
                .isEqualTo("🔢 Цього тижня — <b>ЗНАМЕННИК</b> (8-й тиждень)\n<i>I семестр 2026/2027</i>");
    }

    @Test
    void coreOutageIsReportedHonestlyWithARetryButton() {
        owner();
        subscriber(OWNER_CHAT, CoreFixtures.GROUP_4KI1, "4КІ1");
        core.setUnavailable(true);
        say(OWNER_CHAT, "📅 Сьогодні");
        Sent sorry = telegram.lastSentTo(OWNER_CHAT);
        assertThat(sorry.text()).isEqualTo(BotTexts.CORE_DOWN);
        assertThat(sorry.buttons()).containsExactly("🔄 Спробувати ще раз");

        core.setUnavailable(false);
        press(OWNER_CHAT, sorry.messageId(), sorry.callbackOf("🔄 Спробувати ще раз").orElseThrow());
        assertThat(telegram.lastEditIn(OWNER_CHAT).text()).startsWith("👥 <b>4КІ1</b>");
    }

    @Test
    void teacherSeesHisOwnLessonsTomorrow() {
        teacherChat(TEACHER, CoreFixtures.TEACHER_NEPYIVODA, "Непийвода М.В.");
        say(TEACHER, "🧑‍🏫 Мої пари завтра");
        assertThat(telegram.lastSentTo(TEACHER).text())
                .startsWith("🧑‍🏫 <b>Непийвода М.В.</b> · вівторок, 20 жовтня")
                .contains("4КІ1 · НДКС · ауд. 6");

        owner();
        say(OWNER_CHAT, "🧑‍🏫 Мої пари завтра");
        assertThat(telegram.lastSentTo(OWNER_CHAT).text()).isEqualTo(BotTexts.TEACHERS_ONLY);
    }

    @Test
    void inlineModeSharesCardsWithBoundUsersOnly() {
        owner();
        dispatcher.dispatch(new InlineRequest(1, "q-1", OWNER_CHAT, "4КІ1"));
        var answer = telegram.inlineAnswers().getLast();
        assertThat(answer.articles()).extracting(article -> article.title())
                .containsExactly("4КІ1 — сьогодні, Пн 19.10", "4КІ1 — завтра, Вт 20.10");
        assertThat(answer.articles().getFirst().description()).isEqualTo("2 пари · змін: 2");
        assertThat(answer.articles().getFirst().html()).startsWith("👥 <b>4КІ1</b> · понеділок, 19 жовтня");

        dispatcher.dispatch(new InlineRequest(2, "q-2", STRANGER, "4КІ1"));
        assertThat(telegram.inlineAnswers().getLast().articles()).isEmpty();
    }

    @Test
    void unknownCommandAndHelp() {
        owner();
        say(OWNER_CHAT, "/nonsense");
        assertThat(telegram.lastSentTo(OWNER_CHAT).text()).startsWith("Не знаю такої команди");
        say(OWNER_CHAT, "/help");
        assertThat(telegram.lastSentTo(OWNER_CHAT).text()).startsWith("ℹ️ <b>Що я вмію</b>")
                .contains("<code>@scheduler_vtfk_bot 4КІ1</code>");
    }

    private void subscriberCodeFor(long chatId) {
        String code = issue(BindingTarget.group(BindingType.SUBSCRIBER, CoreFixtures.GROUP_4KI2, "4КІ2"));
        say(chatId, "/start " + code);
        assertThat(telegram.lastSentTo(chatId).text()).startsWith("✅ Готово! Ви підписані на розклад групи <b>4КІ2</b>");
    }
}
