package ua.vtc.rozklad.notification.bot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.notification.binding.BindingCodes;
import ua.vtc.rozklad.notification.binding.BindingTarget;
import ua.vtc.rozklad.notification.binding.BindingType;
import ua.vtc.rozklad.notification.binding.ChatBinding;
import ua.vtc.rozklad.notification.bot.BotUpdate.ChatMigration;
import ua.vtc.rozklad.notification.bot.BotUpdate.MembershipChange;
import ua.vtc.rozklad.notification.support.CoreFixtures;
import ua.vtc.rozklad.notification.support.IntegrationTest;

/** Group chats: /bind, /today, /tomorrow; membership changes; leaked teacher codes. */
class GroupChatFlowsIT extends IntegrationTest {

    private static final long GROUP_CHAT = -1001234567890L;

    @Test
    void bindCommandBindsTheChatToItsGroup() {
        BindingCodes.IssuedCode code = codes.issue(BindingTarget.group(BindingType.GROUP_CHAT, CoreFixtures.GROUP_4KI1,
                "4КІ1"), "admin");
        sayInGroup(GROUP_CHAT, code.command());

        assertThat(telegram.lastSentTo(GROUP_CHAT).text()).startsWith("✅ Чат прив’язано до групи <b>4КІ1</b>.")
                .contains("/today");
        assertThat(bindings.groupChat(GROUP_CHAT)).get().satisfies(binding -> {
            assertThat(binding.getGroupId()).isEqualTo(CoreFixtures.GROUP_4KI1);
            assertThat(binding.getTitle()).isEqualTo("Чат групи → 4КІ1");
        });
    }

    @Test
    void startgroupDeepLinkBindsToo() {
        String code = issue(BindingTarget.group(BindingType.GROUP_CHAT, CoreFixtures.GROUP_4KI2, "4КІ2"));
        sayInGroup(GROUP_CHAT, "/start@scheduler_vtfk_bot " + code);
        assertThat(bindings.groupChat(GROUP_CHAT)).get().extracting(ChatBinding::getGroupCode).isEqualTo("4КІ2");
    }

    @Test
    void boundChatGetsTodayAndTomorrowOfItsGroup() {
        groupChatOf(GROUP_CHAT, CoreFixtures.GROUP_4KI1, "4КІ1");
        sayInGroup(GROUP_CHAT, "/today@scheduler_vtfk_bot");
        assertThat(telegram.lastSentTo(GROUP_CHAT).text()).startsWith("👥 <b>4КІ1</b> · понеділок, 19 жовтня");
        // у груповому чаті немає кнопки «Зробити моєю групою»
        assertThat(telegram.lastSentTo(GROUP_CHAT).buttons()).containsExactly("◀️ Пт 16.10", "Вт 20.10 ▶️");

        sayInGroup(GROUP_CHAT, "/tomorrow");
        assertThat(telegram.lastSentTo(GROUP_CHAT).text()).startsWith("👥 <b>4КІ1</b> · вівторок, 20 жовтня");

        sayInGroup(GROUP_CHAT, "/help");
        assertThat(telegram.lastSentTo(GROUP_CHAT).text()).startsWith("ℹ️ <b>Команди для чату групи</b>");
    }

    @Test
    void chatterAndOtherBotsAreIgnored() {
        groupChatOf(GROUP_CHAT, CoreFixtures.GROUP_4KI1, "4КІ1");
        sayInGroup(GROUP_CHAT, "Хто знає, що завтра?");
        sayInGroup(GROUP_CHAT, "/today@other_bot");
        sayInGroup(GROUP_CHAT, "/start");
        assertThat(telegram.sentTo(GROUP_CHAT)).extracting(sent -> sent.text())
                .containsExactly("Надішліть код прив’язки так: /bind КОД — код дає диспетчер у веб-панелі.");
    }

    @Test
    void unboundGroupGetsNothingButABoundAdminChatIsTold() {
        sayInGroup(GROUP_CHAT, "/today");
        assertThat(telegram.sentTo(GROUP_CHAT)).isEmpty();

        bindings.bind(BindingTarget.adminChat(), groupChat(GROUP_CHAT, "Диспетчерська"));
        sayInGroup(GROUP_CHAT, "/today");
        assertThat(telegram.lastSentTo(GROUP_CHAT).text()).startsWith("Цей чат ще не прив’язаний до групи.");
    }

    @Test
    void teacherCodePostedInAGroupIsBurned() {
        String code = issue(BindingTarget.teacher(CoreFixtures.TEACHER_NEPYIVODA, "Непийвода М.В."));
        sayInGroup(GROUP_CHAT, "/bind " + code);
        assertThat(telegram.lastSentTo(GROUP_CHAT).text()).contains("код анульовано");
        assertThat(bindings.isKnownChat(GROUP_CHAT)).isFalse();

        say(2001, "/start " + code);
        assertThat(telegram.lastSentTo(2001).text()).contains("уже використано");
        assertThat(bindings.teacherBinding(2001)).isEmpty();
    }

    @Test
    void groupCodeInAPrivateChatIsExplainedAndNotSpent() {
        String code = issue(BindingTarget.group(BindingType.GROUP_CHAT, CoreFixtures.GROUP_4KI1, "4КІ1"));
        say(3001, "/start " + code);
        assertThat(telegram.lastSentTo(3001).text()).startsWith("Цей код — для чату групи.");
        sayInGroup(GROUP_CHAT, "/bind " + code);
        assertThat(bindings.groupChat(GROUP_CHAT)).isPresent();
    }

    @Test
    void removingTheBotForgetsTheChatAndAnUpgradeMovesIt() {
        groupChatOf(GROUP_CHAT, CoreFixtures.GROUP_4KI1, "4КІ1");
        dispatcher.dispatch(new ChatMigration(1, GROUP_CHAT, -1009998887776L));
        assertThat(bindings.groupChat(GROUP_CHAT)).isEmpty();
        assertThat(bindings.groupChat(-1009998887776L)).isPresent();

        dispatcher.dispatch(new MembershipChange(2, groupChat(-1009998887776L, "Чат 4КІ1"), false));
        assertThat(bindings.isKnownChat(-1009998887776L)).isFalse();
    }

    @Test
    void botAddedToABoundGroupGreetsButStaysSilentElsewhere() {
        dispatcher.dispatch(new MembershipChange(1, groupChat(GROUP_CHAT, "Новий чат"), true));
        assertThat(telegram.sentTo(GROUP_CHAT)).isEmpty();

        groupChatOf(GROUP_CHAT, CoreFixtures.GROUP_4KI1, "4КІ1");
        dispatcher.dispatch(new MembershipChange(2, groupChat(GROUP_CHAT, "Новий чат"), true));
        assertThat(telegram.lastSentTo(GROUP_CHAT).text()).startsWith("Вітаю! Я бот розкладу ВТФК");
    }
}
