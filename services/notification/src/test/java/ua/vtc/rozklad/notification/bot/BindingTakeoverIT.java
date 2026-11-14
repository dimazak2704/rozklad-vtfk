package ua.vtc.rozklad.notification.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.notification.binding.BindingTarget;
import ua.vtc.rozklad.notification.binding.BindingType;
import ua.vtc.rozklad.notification.bot.BotUpdate.ChatKind;
import ua.vtc.rozklad.notification.bot.BotUpdate.ChatMessage;
import ua.vtc.rozklad.notification.bot.BotUpdate.ChatRef;
import ua.vtc.rozklad.notification.support.CoreFixtures;
import ua.vtc.rozklad.notification.support.IntegrationTest;
import ua.vtc.rozklad.notification.telegram.TelegramApi.MemberStatus;
import ua.vtc.rozklad.notification.telegram.TelegramApiFailure;

/**
 * A binding code works for whoever redeems it first. What limits the damage of a code seen by the wrong person
 * (a QR on a projector, a link forwarded to a student chat): short life of personal codes, a notice to the chat
 * that lost the teacher, the linked account visible in the cabinet, self-service unlinking, and chat admins only
 * for service-chat codes and re-binding in group chats.
 */
class BindingTakeoverIT extends IntegrationTest {

    private static final long TEACHER_PHONE = 7101;
    private static final long STRANGER = 7102;
    private static final long STUDENT_GROUP = -1007100000001L;
    private static final long MEMBER = 42;
    private static final long CHAT_ADMIN = 77;
    private static final String ADMINS_ONLY = "Застосувати цей код у груповому чаті може лише адміністратор чату.";
    private static final AtomicLong IDS = new AtomicLong(9_000);

    @Test
    void codesForATeacherOrAServiceChatLiveFifteenMinutesGroupCodesADay() {
        String teacherCode = issue(BindingTarget.teacher(CoreFixtures.TEACHER_KRENTSIN, "Кренцін М.Д."));
        String serviceCode = issue(BindingTarget.adminChat());
        String subscriberCode = issue(BindingTarget.group(BindingType.SUBSCRIBER, CoreFixtures.GROUP_4KI1, "4КІ1"));

        clock.advance(Duration.ofMinutes(16));
        say(STRANGER, "/start " + teacherCode);
        assertThat(bindings.teacherBinding(STRANGER)).isEmpty();
        assertThat(telegram.lastSentTo(STRANGER).text()).isEqualTo("Термін дії коду минув. Попросіть новий у веб-панелі.");
        say(STRANGER, "/start " + serviceCode);
        assertThat(bindings.find(STRANGER, BindingType.ADMIN_CHAT)).isEmpty();

        say(STRANGER, "/start " + subscriberCode);
        assertThat(bindings.subscription(STRANGER)).isPresent();
    }

    @Test
    void theChatThatLostTheTeacherIsTold() {
        teacherChat(TEACHER_PHONE, CoreFixtures.TEACHER_NEPYIVODA, "Непийвода М.В.");
        String fresh = issue(BindingTarget.teacher(CoreFixtures.TEACHER_NEPYIVODA, "Непийвода М.В."));

        say(STRANGER, "/start " + fresh);

        assertThat(bindings.teacherChat(CoreFixtures.TEACHER_NEPYIVODA)).get()
                .extracting(binding -> binding.getChatId()).isEqualTo(STRANGER);
        assertThat(telegram.lastSentTo(TEACHER_PHONE).text())
                .contains("Telegram викладача <b>Непийвода М.В.</b> щойно підключили в іншому чаті")
                .contains("повідомте диспетчера");

        // повторний код у тому самому чаті нікого не витісняє
        say(STRANGER, "/start " + issue(BindingTarget.teacher(CoreFixtures.TEACHER_NEPYIVODA, "Непийвода М.В.")));
        assertThat(telegram.sentTo(TEACHER_PHONE)).hasSize(1);
    }

    @Test
    void theCabinetNamesTheLinkedTelegramAccountAndTheTeacherUnlinksIt() throws Exception {
        String code = issue(BindingTarget.teacher(CoreFixtures.TEACHER_NEPYIVODA, "Непийвода М.В."));
        dispatcher.dispatch(new ChatMessage(IDS.incrementAndGet(), new ChatRef(STRANGER, ChatKind.PRIVATE, "Студент"),
                STRANGER, IDS.incrementAndGet(), "/start " + code));

        mockMvc.perform(get("/api/notify/bindings/me").with(teacher("teacher-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bound").value(true))
                .andExpect(jsonPath("$.binding.telegramAccount").value("Студент"));
        mockMvc.perform(get("/api/notify/bindings").with(admin()))
                .andExpect(jsonPath("$[0].telegramAccount").value("Студент"));

        mockMvc.perform(delete("/api/notify/bindings/me").with(teacher("teacher-token")))
                .andExpect(status().isNoContent());
        assertThat(bindings.teacherChat(CoreFixtures.TEACHER_NEPYIVODA)).isEmpty();
        // повторно — те саме 204, нічого не зламано
        mockMvc.perform(delete("/api/notify/bindings/me").with(teacher("teacher-token")))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/notify/bindings/me").with(teacher("unlinked-token")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("teacher-not-linked"));
        mockMvc.perform(delete("/api/notify/bindings/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void theTeacherUnlinksOnlyHisOwnChat() throws Exception {
        teacherChat(TEACHER_PHONE, CoreFixtures.TEACHER_KRENTSIN, "Кренцін М.Д.");

        mockMvc.perform(delete("/api/notify/bindings/me").with(teacher("teacher-token")))
                .andExpect(status().isNoContent());

        assertThat(bindings.teacherChat(CoreFixtures.TEACHER_KRENTSIN)).isPresent();
    }

    @Test
    void anOrdinaryMemberCannotMoveABoundGroupChatToAnotherGroup() {
        groupChatOf(STUDENT_GROUP, CoreFixtures.GROUP_4KI1, "4КІ1");
        String otherGroup = issue(BindingTarget.group(BindingType.GROUP_CHAT, CoreFixtures.GROUP_4KI2, "4КІ2"));

        sayInGroupAs(MEMBER, "/bind " + otherGroup);

        assertThat(bindings.groupChat(STUDENT_GROUP)).get().extracting(b -> b.getGroupCode()).isEqualTo("4КІ1");
        assertThat(telegram.lastSentTo(STUDENT_GROUP).text()).isEqualTo(ADMINS_ONLY);

        // код не витрачено: адміністратор чату застосовує його
        telegram.setMemberStatus(STUDENT_GROUP, CHAT_ADMIN, MemberStatus.ADMINISTRATOR);
        sayInGroupAs(CHAT_ADMIN, "/bind " + otherGroup);
        assertThat(bindings.groupChat(STUDENT_GROUP)).get().extracting(b -> b.getGroupCode()).isEqualTo("4КІ2");
    }

    @Test
    void onlyAChatAdminMakesAGroupChatAServiceChat() {
        String service = issue(BindingTarget.adminChat());

        sayInGroupAs(MEMBER, "/bind " + service);
        assertThat(bindings.find(STUDENT_GROUP, BindingType.ADMIN_CHAT)).isEmpty();
        assertThat(telegram.lastSentTo(STUDENT_GROUP).text()).isEqualTo(ADMINS_ONLY);

        // Telegram не відповів — не прив'язуємо (fail closed), код лишається
        telegram.failNextMemberStatus(new TelegramApiFailure(502, "Bad Gateway", null, null, null));
        sayInGroupAs(CHAT_ADMIN, "/bind " + service);
        assertThat(bindings.find(STUDENT_GROUP, BindingType.ADMIN_CHAT)).isEmpty();
        assertThat(telegram.lastSentTo(STUDENT_GROUP).text()).isEqualTo("Не вдалося перевірити, чи ви адміністратор чату. Спробуйте за хвилину.");

        telegram.setMemberStatus(STUDENT_GROUP, CHAT_ADMIN, MemberStatus.CREATOR);
        sayInGroupAs(CHAT_ADMIN, "/bind " + service);
        assertThat(bindings.find(STUDENT_GROUP, BindingType.ADMIN_CHAT)).isPresent();
    }

    @Test
    void anyMemberStillBindsANewGroupChatToItsGroup() {
        String code = issue(BindingTarget.group(BindingType.GROUP_CHAT, CoreFixtures.GROUP_4KI1, "4КІ1"));

        sayInGroupAs(MEMBER, "/bind " + code);

        assertThat(bindings.groupChat(STUDENT_GROUP)).get().extracting(b -> b.getGroupCode()).isEqualTo("4КІ1");
        assertThat(telegram.memberStatusCalls()).isZero();
    }

    private void sayInGroupAs(long userId, String text) {
        dispatcher.dispatch(new ChatMessage(IDS.incrementAndGet(), new ChatRef(STUDENT_GROUP, ChatKind.GROUP, "Чат 4КІ1"),
                userId, IDS.incrementAndGet(), text));
    }
}
