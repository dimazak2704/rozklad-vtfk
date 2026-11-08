package ua.vtc.rozklad.notification.bot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import ua.vtc.rozklad.notification.binding.BindingTarget;
import ua.vtc.rozklad.notification.binding.BindingType;
import ua.vtc.rozklad.notification.bot.BotUpdate.ChatMessage;
import ua.vtc.rozklad.notification.support.CoreFixtures;
import ua.vtc.rozklad.notification.support.IntegrationTest;

/** Guessing binding codes: five wrong codes per chat and user within 15 minutes, then nothing is looked up. */
class BindingAttemptsIT extends IntegrationTest {

    private static final long GROUP_CHAT = -1001234567890L;
    private static final long OTHER_GROUP = -1009876543210L;
    /** Valid alphabet, but never issued. */
    private static final String[] GUESSES = {"AAAA-AAAA", "BBBB-BBBB", "CCCC-CCCC", "DDDD-DDDD", "EEEE-EEEE"};

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void aBoundChatIsToldToWaitAfterFiveWrongCodesAndEvenARealCodeIsNotLookedUp() {
        groupChatOf(GROUP_CHAT, CoreFixtures.GROUP_4KI1, "4КІ1");
        for (String guess : GUESSES) {
            sayInGroup(GROUP_CHAT, "/bind " + guess);
            assertThat(telegram.lastSentTo(GROUP_CHAT).text()).startsWith("Код не знайдено.");
        }
        String real = issue(BindingTarget.group(BindingType.GROUP_CHAT, CoreFixtures.GROUP_4KI2, "4КІ2"));
        sayInGroup(GROUP_CHAT, "/bind " + real);
        assertThat(telegram.lastSentTo(GROUP_CHAT).text())
                .isEqualTo("Забагато невдалих спроб. Спробуйте ще раз через 15 хв.");
        assertThat(bindings.groupChat(GROUP_CHAT)).get().extracting(b -> b.getGroupCode()).isEqualTo("4КІ1");

        // лічильник живе 15 хвилин від першої помилки
        Long ttl = redis.getExpire("rozklad:notification:bind-fail:chat:" + GROUP_CHAT);
        assertThat(ttl).isBetween(1L, 900L);
        // вікно минуло: справжній код знову перевіряється і не був витрачений під час блокування
        redis.delete("rozklad:notification:bind-fail:chat:" + GROUP_CHAT);
        redis.delete("rozklad:notification:bind-fail:user:42");
        sayInGroup(GROUP_CHAT, "/bind " + real);
        assertThat(bindings.groupChat(GROUP_CHAT)).get().extracting(b -> b.getGroupCode()).isEqualTo("4КІ2");
    }

    @Test
    void anUnknownChatStaysUnansweredAndCannotKeepGuessing() {
        for (String guess : GUESSES) {
            say(5001, "/start " + guess.replace("-", ""));
        }
        String real = issue(BindingTarget.group(BindingType.SUBSCRIBER, CoreFixtures.GROUP_4KI1, "4КІ1"));
        say(5001, "/start " + real);
        // невідомому чату не відповідаємо взагалі: ні на вигадані коди, ні про блокування
        assertThat(telegram.sentTo(5001)).isEmpty();
        assertThat(bindings.subscription(5001)).isEmpty();

        // той самий код в іншому чаті працює: він не витрачений
        say(5002, "/start " + real);
        assertThat(bindings.subscription(5002)).isPresent();
    }

    @Test
    void theSameUserIsLimitedInEveryChat() {
        for (String guess : GUESSES) {
            sayInGroup(GROUP_CHAT, "/bind " + guess);
        }
        // той самий користувач (42) пробує в іншій групі: його спроби вже вичерпано
        String real = issue(BindingTarget.group(BindingType.GROUP_CHAT, CoreFixtures.GROUP_4KI1, "4КІ1"));
        sayInGroup(OTHER_GROUP, "/bind " + real);
        assertThat(bindings.groupChat(OTHER_GROUP)).isEmpty();

        // інший користувач в іншій групі — без обмежень
        dispatcher.dispatch(new ChatMessage(700, groupChat(OTHER_GROUP, "Чат 4КІ1"), 43, 701, "/bind " + real));
        assertThat(bindings.groupChat(OTHER_GROUP)).isPresent();
    }

    @Test
    void successfulCodesAndUsedCodesCountCorrectly() {
        // правильний код не рахується як помилка; використаний і чужого типу — рахуються
        String first = issue(BindingTarget.group(BindingType.GROUP_CHAT, CoreFixtures.GROUP_4KI1, "4КІ1"));
        sayInGroup(GROUP_CHAT, "/bind " + first);
        assertThat(redis.hasKey("rozklad:notification:bind-fail:chat:" + GROUP_CHAT)).isFalse();

        sayInGroup(GROUP_CHAT, "/bind " + first);
        assertThat(telegram.lastSentTo(GROUP_CHAT).text()).startsWith("Цей код уже використано.");
        assertThat(redis.opsForValue().get("rozklad:notification:bind-fail:chat:" + GROUP_CHAT)).isEqualTo("1");
    }
}
