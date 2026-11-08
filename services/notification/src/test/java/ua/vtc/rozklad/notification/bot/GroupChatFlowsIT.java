package ua.vtc.rozklad.notification.bot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.notification.binding.BindingCodes;
import ua.vtc.rozklad.notification.binding.BindingTarget;
import ua.vtc.rozklad.notification.binding.BindingType;
import ua.vtc.rozklad.notification.binding.ChatBinding;
import ua.vtc.rozklad.notification.bot.BotUpdate.ChatMessage;
import ua.vtc.rozklad.notification.bot.BotUpdate.ChatMigration;
import ua.vtc.rozklad.notification.bot.BotUpdate.MembershipChange;
import ua.vtc.rozklad.notification.support.CoreFixtures;
import ua.vtc.rozklad.notification.support.IntegrationTest;
import ua.vtc.rozklad.notification.telegram.TelegramApi.MemberStatus;
import ua.vtc.rozklad.notification.telegram.TelegramApiFailure;

/** Group chats: /bind, /today, /tomorrow, /status, /rozklad_on|off; membership changes; leaked teacher codes. */
class GroupChatFlowsIT extends IntegrationTest {

    private static final long GROUP_CHAT = -1001234567890L;

    @Test
    void bindCommandBindsTheChatToItsGroup() {
        BindingCodes.IssuedCode code = codes.issue(BindingTarget.group(BindingType.GROUP_CHAT, CoreFixtures.GROUP_4KI1,
                "4КІ1"), "admin");
        sayInGroup(GROUP_CHAT, code.command());

        assertThat(telegram.lastSentTo(GROUP_CHAT).text()).isEqualTo("""
                ✅ Чат прив’язано до групи <b>4КІ1</b>.
                Після кожного розпорядження сюди надходитимуть зміни для групи та зображення розпорядження.
                Ранковий розклад о 7:30 вимкнено. Увімкнути: /rozklad_on (лише адміністратор чату).
                Команди: /today — розклад на сьогодні, /tomorrow — на завтра, /status — що отримує чат.""");
        assertThat(bindings.groupChat(GROUP_CHAT)).get().satisfies(binding -> {
            assertThat(binding.getGroupId()).isEqualTo(CoreFixtures.GROUP_4KI1);
            assertThat(binding.getTitle()).isEqualTo("Чат групи → 4КІ1");
            // ранковий розклад у чаті групи вмикають самі, спершу він вимкнений
            assertThat(binding.isDigestEnabled()).isFalse();
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
        assertThat(telegram.lastSentTo(GROUP_CHAT).text()).startsWith("ℹ️ <b>Команди для чату групи</b>")
                .contains("/status — що отримує цей чат")
                .contains("/rozklad_on, /rozklad_off — увімкнути чи вимкнути ранковий розклад о 7:30")
                .endsWith("Заміни після публікації розпорядження приходять завжди. "
                        + "Ранковий розклад вмикає адміністратор чату — спершу він вимкнений.");
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

    @Test
    void statusSaysWhatTheChatReceives() {
        groupChatOf(GROUP_CHAT, CoreFixtures.GROUP_4KI1, "4КІ1");
        sayInGroup(GROUP_CHAT, "/status");
        assertThat(telegram.lastSentTo(GROUP_CHAT).text()).isEqualTo("""
                ℹ️ Чат прив’язано до групи <b>4КІ1</b>.
                Цей чат отримує:
                • заміни після публікації розпорядження ✅
                • ранковий розклад о 7:30 — вимкнено. Увімкнути: /rozklad_on""");
        // статус може переглянути будь-хто: права в Telegram не перевіряємо
        assertThat(telegram.memberStatusCalls()).isZero();
    }

    @Test
    void onlyAChatAdminSwitchesTheMorningSchedule() {
        groupChatOf(GROUP_CHAT, CoreFixtures.GROUP_4KI1, "4КІ1");
        sayInGroup(GROUP_CHAT, "/rozklad_on@scheduler_vtfk_bot");
        assertThat(telegram.lastSentTo(GROUP_CHAT).text()).isEqualTo("Змінити це може лише адміністратор чату.");
        assertThat(digestOn()).isFalse();

        telegram.setMemberStatus(GROUP_CHAT, 77, MemberStatus.ADMINISTRATOR);
        sayInGroupAs(77, "/rozklad_on");
        assertThat(telegram.lastSentTo(GROUP_CHAT).text()).isEqualTo("✅ Ранковий розклад увімкнено: щоранку о 7:30 "
                + "у навчальні дні тут буде розклад групи <b>4КІ1</b>. Вимкнути: /rozklad_off");
        assertThat(digestOn()).isTrue();
        sayInGroup(GROUP_CHAT, "/status");
        assertThat(telegram.lastSentTo(GROUP_CHAT).text())
                .endsWith("• ранковий розклад о 7:30 ✅ Вимкнути: /rozklad_off");

        telegram.setMemberStatus(GROUP_CHAT, 78, MemberStatus.CREATOR);
        sayInGroupAs(78, "/rozklad_off");
        assertThat(telegram.lastSentTo(GROUP_CHAT).text()).isEqualTo("Ранковий розклад вимкнено. Заміни після "
                + "публікації розпорядження приходитимуть, як і раніше. Увімкнути: /rozklad_on");
        assertThat(digestOn()).isFalse();
    }

    @Test
    void theAdminCheckIsCachedAndAnAnonymousAdminNeedsNoCheck() {
        groupChatOf(GROUP_CHAT, CoreFixtures.GROUP_4KI1, "4КІ1");
        telegram.setMemberStatus(GROUP_CHAT, 77, MemberStatus.ADMINISTRATOR);
        sayInGroupAs(77, "/rozklad_on");
        sayInGroupAs(77, "/rozklad_off");
        sayInGroupAs(77, "/rozklad_on");
        assertThat(telegram.memberStatusCalls()).isEqualTo(1);
        // відмову теж пам'ятаємо: учасник, що надсилає команду знову й знову, не смикає Telegram щоразу
        sayInGroup(GROUP_CHAT, "/rozklad_off");
        sayInGroup(GROUP_CHAT, "/rozklad_off");
        assertThat(telegram.memberStatusCalls()).isEqualTo(2);
        assertThat(digestOn()).isTrue();

        // анонімний адміністратор пише від імені групи — Telegram не питаємо
        dispatcher.dispatch(new ChatMessage(900, groupChat(GROUP_CHAT, "Чат групи"), 1087968824L, 901, "/rozklad_off",
                GROUP_CHAT));
        assertThat(digestOn()).isFalse();
        assertThat(telegram.memberStatusCalls()).isEqualTo(2);
    }

    @Test
    void aFailedAdminCheckChangesNothingAndIsNotRemembered() {
        groupChatOf(GROUP_CHAT, CoreFixtures.GROUP_4KI1, "4КІ1");
        telegram.setMemberStatus(GROUP_CHAT, 77, MemberStatus.ADMINISTRATOR);
        telegram.failNextMemberStatus(TelegramApiFailure.network("timeout", null));
        sayInGroupAs(77, "/rozklad_on");
        assertThat(telegram.lastSentTo(GROUP_CHAT).text())
                .isEqualTo("Не вдалося перевірити, чи ви адміністратор чату. Спробуйте за хвилину.");
        assertThat(digestOn()).isFalse();

        sayInGroupAs(77, "/rozklad_on");
        assertThat(digestOn()).isTrue();
    }

    @Test
    void switchesNeedAGroupBindingAndUnknownChatsGetNothing() {
        sayInGroup(GROUP_CHAT, "/rozklad_on");
        sayInGroup(GROUP_CHAT, "/status");
        assertThat(telegram.sentTo(GROUP_CHAT)).isEmpty();

        // службовий груповий чат не прив'язаний до групи: перемикати нічого, Telegram не питаємо
        bindings.bind(BindingTarget.adminChat(), groupChat(GROUP_CHAT, "Диспетчерська"));
        sayInGroup(GROUP_CHAT, "/rozklad_on");
        assertThat(telegram.lastSentTo(GROUP_CHAT).text()).startsWith("Цей чат ще не прив’язаний до групи.");
        sayInGroup(GROUP_CHAT, "/status");
        assertThat(telegram.lastSentTo(GROUP_CHAT).text()).startsWith("Цей чат ще не прив’язаний до групи.");
        assertThat(telegram.memberStatusCalls()).isZero();
    }

    @Test
    void rebindingAChatKeepsItsMorningScheduleChoice() {
        groupChatOf(GROUP_CHAT, CoreFixtures.GROUP_4KI1, "4КІ1");
        bindings.setGroupChatDigest(GROUP_CHAT, true);
        sayInGroup(GROUP_CHAT, "/bind " + issue(BindingTarget.group(BindingType.GROUP_CHAT, CoreFixtures.GROUP_4KI2,
                "4КІ2")));
        assertThat(telegram.lastSentTo(GROUP_CHAT).text())
                .contains("Ранковий розклад о 7:30 увімкнено. Вимкнути: /rozklad_off (лише адміністратор чату).");
        assertThat(bindings.groupChat(GROUP_CHAT)).get().satisfies(binding -> {
            assertThat(binding.getGroupCode()).isEqualTo("4КІ2");
            assertThat(binding.isDigestEnabled()).isTrue();
        });
    }

    private boolean digestOn() {
        return bindings.groupChat(GROUP_CHAT).orElseThrow().isDigestEnabled();
    }

    private void sayInGroupAs(long userId, String text) {
        dispatcher.dispatch(new ChatMessage(System.nanoTime(), groupChat(GROUP_CHAT, "Чат групи"), userId,
                System.nanoTime(), text));
    }
}
