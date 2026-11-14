package ua.vtc.rozklad.notification.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.notification.bot.BotUpdate.ChatKind;
import ua.vtc.rozklad.notification.bot.BotUpdate.ChatRef;

/** The account that linked a teacher chat, as the cabinet and the dispatcher see it. */
class TelegramAccountTest {

    @Test
    void nameAndUsernameAsTelegramSentThem() {
        assertThat(ChatBindings.telegramAccount(chat("Михайло", "krentsin_m"))).isEqualTo("Михайло (@krentsin_m)");
        assertThat(ChatBindings.telegramAccount(chat("Михайло", null))).isEqualTo("Михайло");
        assertThat(ChatBindings.telegramAccount(chat(null, "krentsin_m"))).isEqualTo("@krentsin_m");
        assertThat(ChatBindings.telegramAccount(chat("  ", null))).isNull();
    }

    @Test
    void controlCharactersAreDroppedAndTheLengthFitsTheColumn() {
        assertThat(ChatBindings.telegramAccount(chat("Ми\nхайло\u0007", null))).isEqualTo("Ми хайло");
        assertThat(ChatBindings.telegramAccount(chat("я".repeat(150), "nick"))).hasSize(100);
    }

    @Test
    void onlyATeacherChatRecordsIt() {
        Instant now = Instant.parse("2026-11-08T10:00:00Z");
        ChatBinding teacher = ChatBinding.teacher(200, UUID.randomUUID(), "Непийвода М.В.", now);
        teacher.linkedBy("Михайло", now);
        assertThat(teacher.getTelegramAccount()).isEqualTo("Михайло");

        assertThatThrownBy(() -> ChatBinding.adminChat(300, "Диспетчерська", now).linkedBy("Михайло", now))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ChatRef chat(String firstName, String username) {
        return new ChatRef(42, ChatKind.PRIVATE, firstName, username);
    }
}
