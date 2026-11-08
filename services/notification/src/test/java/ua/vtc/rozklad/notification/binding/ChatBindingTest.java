package ua.vtc.rozklad.notification.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Who gets the 07:30 schedule by default and who can get it at all. */
class ChatBindingTest {

    private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
    private static final UUID GROUP = UUID.randomUUID();

    @Test
    void aNewGroupChatStartsWithoutTheMorningScheduleAndASubscriberWithIt() {
        assertThat(ChatBinding.groupChat(-100, GROUP, "4КІ1", "Чат → 4КІ1", NOW).isDigestEnabled()).isFalse();
        assertThat(ChatBinding.subscriber(100, GROUP, "4КІ1", NOW).isDigestEnabled()).isTrue();
    }

    @Test
    void onlyGroupChatsAndSubscribersCanSwitchIt() {
        ChatBinding groupChat = ChatBinding.groupChat(-100, GROUP, "4КІ1", "Чат → 4КІ1", NOW);
        groupChat.setDigestEnabled(true, NOW.plusSeconds(60));
        assertThat(groupChat.isDigestEnabled()).isTrue();
        assertThat(groupChat.getUpdatedAt()).isEqualTo(NOW.plusSeconds(60));

        assertThatThrownBy(() -> ChatBinding.teacher(200, UUID.randomUUID(), "Непийвода М.В.", NOW)
                .setDigestEnabled(true, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ChatBinding.adminChat(300, "Диспетчерська", NOW).setDigestEnabled(true, NOW))
                .isInstanceOf(IllegalStateException.class);
    }
}
