package ua.vtc.rozklad.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ua.vtc.rozklad.notification.support.CoreFixtures;
import ua.vtc.rozklad.notification.support.IntegrationTest;

/** REST of CONTRACTS §6: binding codes, bindings, bot status and the Telegram webhook. */
class NotifyApiIT extends IntegrationTest {

    private static final String CODE = "[ABCDEFGHJKLMNPQRSTUVWXYZ2-9]{4}-[ABCDEFGHJKLMNPQRSTUVWXYZ2-9]{4}";

    @Test
    void adminCreatesCodesForEveryBindingType() throws Exception {
        createCode(admin(), "{\"type\":\"GROUP_CHAT\",\"groupId\":\"" + CoreFixtures.GROUP_4KI1 + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(matchesPattern(CODE)))
                .andExpect(jsonPath("$.deepLink").value(matchesPattern(
                        "https://t\\.me/scheduler_vtfk_bot\\?start=[A-Z2-9]{8}")))
                .andExpect(jsonPath("$.groupLink").value(startsWith("https://t.me/scheduler_vtfk_bot?startgroup=")))
                .andExpect(jsonPath("$.command").value(matchesPattern("/bind " + CODE)))
                .andExpect(jsonPath("$.label").value("4КІ1"))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-20T05:00:00Z"));
        createCode(admin(), "{\"type\":\"TEACHER\",\"teacherId\":\"" + CoreFixtures.TEACHER_NEPYIVODA + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Непийвода М.В."))
                .andExpect(jsonPath("$.groupLink").value(nullValue()));
        createCode(admin(), "{\"type\":\"SUBSCRIBER\",\"groupId\":\"" + CoreFixtures.GROUP_4KI2 + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.label").value("4КІ2"));
        createCode(admin(), "{\"type\":\"ADMIN_CHAT\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.label").value("Службовий чат"));
    }

    @Test
    void invalidRequestsAreExplained() throws Exception {
        createCode(admin(), "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("type"))
                .andExpect(jsonPath("$.errors[0].message").value("Оберіть тип прив’язки."));
        createCode(admin(), "{\"type\":\"GROUP_CHAT\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("groupId"))
                .andExpect(jsonPath("$.errors[0].message").value("Оберіть групу."));
        createCode(admin(), "{\"type\":\"TEACHER\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("teacherId"));
        createCode(admin(), "{\"type\":\"GROUP_CHAT\",\"groupId\":\"" + CoreFixtures.SEMESTER + "\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Групу не знайдено."));
        createCode(admin(), "{\"type\":\"PIGEON\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:validation"))
                .andExpect(jsonPath("$.errors[0].field").value("type"))
                .andExpect(jsonPath("$.errors[0].message").value(startsWith("Допустимі значення: ")));
    }

    @Test
    void teacherCreatesOnlyHisOwnCode() throws Exception {
        createCode(teacher("teacher-token"), "{\"type\":\"TEACHER\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Непийвода М.В."));
        createCode(teacher("teacher-token"), "{\"type\":\"TEACHER\",\"teacherId\":\""
                + CoreFixtures.TEACHER_KRENTSIN + "\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Можна створити код лише для власного Telegram."));
        createCode(teacher("teacher-token"), "{\"type\":\"GROUP_CHAT\",\"groupId\":\"" + CoreFixtures.GROUP_4KI1 + "\"}")
                .andExpect(status().isForbidden());
        createCode(teacher("unlinked-token"), "{\"type\":\"TEACHER\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("teacher-not-linked"));
    }

    @Test
    void teacherSeesWhetherHisOwnTelegramIsBound() throws Exception {
        mockMvc.perform(get("/api/notify/bindings/me").with(teacher("teacher-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teacherId").value(CoreFixtures.TEACHER_NEPYIVODA.toString()))
                .andExpect(jsonPath("$.teacherName").value("Непийвода М.В."))
                .andExpect(jsonPath("$.bound").value(false))
                .andExpect(jsonPath("$.binding").value(nullValue()))
                .andExpect(jsonPath("$.botLink").value("https://t.me/scheduler_vtfk_bot"));

        teacherChat(4242L, CoreFixtures.TEACHER_NEPYIVODA, "Непийвода М.В.");
        teacherChat(4343L, CoreFixtures.TEACHER_KRENTSIN, "Кренцін М.Д.");
        mockMvc.perform(get("/api/notify/bindings/me").with(teacher("teacher-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bound").value(true))
                .andExpect(jsonPath("$.binding.type").value("TEACHER"))
                .andExpect(jsonPath("$.binding.teacherId").value(CoreFixtures.TEACHER_NEPYIVODA.toString()))
                .andExpect(jsonPath("$.binding.chatId").value("•••4242"));

        mockMvc.perform(get("/api/notify/bindings/me").with(teacher("unlinked-token")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("teacher-not-linked"));
        mockMvc.perform(get("/api/notify/bindings/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void coreOutageIsAnHonest503() throws Exception {
        core.setUnavailable(true);
        createCode(admin(), "{\"type\":\"GROUP_CHAT\",\"groupId\":\"" + CoreFixtures.GROUP_4KI1 + "\"}")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Сервіс розкладу тимчасово недоступний. Спробуйте за хвилину."));
    }

    @Test
    void bindingsAreListedWithMaskedChatIdsAndCanBeRemoved() throws Exception {
        owner();
        var group = groupChatOf(-1001234567890L, CoreFixtures.GROUP_4KI1, "4КІ1");
        mockMvc.perform(get("/api/notify/bindings").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.type == 'GROUP_CHAT')].chatId").value("-•••7890"))
                .andExpect(jsonPath("$[?(@.type == 'GROUP_CHAT')].groupCode").value("4КІ1"))
                .andExpect(jsonPath("$[?(@.type == 'ADMIN_CHAT')].title").value("Чат власника бота"));

        mockMvc.perform(delete("/api/notify/bindings/" + group.getId()).with(admin())).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/notify/bindings/" + group.getId()).with(admin())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/notify/bindings").with(teacher("teacher-token"))).andExpect(status().isForbidden());
    }

    @Test
    void botStatusSaysWhyItDoesNotReceiveUpdates() throws Exception {
        mockMvc.perform(get("/api/notify/bot").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("scheduler_vtfk_bot"))
                .andExpect(jsonPath("$.link").value("https://t.me/scheduler_vtfk_bot"))
                .andExpect(jsonPath("$.mode").value("WEBHOOK"))
                .andExpect(jsonPath("$.connected").value(false))
                .andExpect(jsonPath("$.problem").value(startsWith("Бот не отримує повідомлень: вебхук не зареєстровано")));
    }

    @Test
    void webhookChecksTheSecretAndDispatchesTheUpdate() throws Exception {
        owner();
        String update = """
                {"update_id":501,"message":{"message_id":5,"date":1760000000,
                 "from":{"id":1000,"is_bot":false,"first_name":"Власник"},
                 "chat":{"id":1000,"type":"private","first_name":"Власник"},"text":"/start"}}""";
        mockMvc.perform(post("/api/notify/telegram/webhook").contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/notify/telegram/webhook").header("X-Telegram-Bot-Api-Secret-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/notify/telegram/webhook").header("X-Telegram-Bot-Api-Secret-Token", WEBHOOK_SECRET)
                        .contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isOk());
        await(Duration.ofSeconds(10), () -> !telegram.sentTo(OWNER_CHAT).isEmpty());
        assertThat(telegram.lastSentTo(OWNER_CHAT).text()).contains("Я бот розкладу ВТФК");

        mockMvc.perform(post("/api/notify/telegram/webhook").header("X-Telegram-Bot-Api-Secret-Token", WEBHOOK_SECRET)
                        .contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest());
    }

    private ResultActions createCode(RequestPostProcessor caller, String body) throws Exception {
        return mockMvc.perform(post("/api/notify/bindings/codes").with(caller)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
