package ua.vtc.rozklad.notification.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.Update;
import ua.vtc.rozklad.notification.bot.BotUpdate;
import ua.vtc.rozklad.notification.bot.BotUpdate.CallbackAction;
import ua.vtc.rozklad.notification.bot.BotUpdate.ChatKind;
import ua.vtc.rozklad.notification.bot.BotUpdate.ChatMessage;
import ua.vtc.rozklad.notification.bot.BotUpdate.ChatMigration;
import ua.vtc.rozklad.notification.bot.BotUpdate.InlineRequest;
import ua.vtc.rozklad.notification.bot.BotUpdate.MembershipChange;

/** Real Bot API JSON (as Telegram sends it) → the bot's own update model. */
class UpdateMapperTest {

    private static final String USER = "{\"id\":42,\"is_bot\":false,\"first_name\":\"Дмитро\"}";

    private final ObjectMapper telegramJson = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void privateTextMessage() throws Exception {
        Optional<BotUpdate> update = map("""
                {"update_id":10,"message":{"message_id":5,"date":1760000000,"from":%s,
                 "chat":{"id":42,"type":"private","first_name":"Дмитро"},"text":"/start K7QXM2PA"}}
                """.formatted(USER));
        assertThat(update).get().isEqualTo(new ChatMessage(10, new BotUpdate.ChatRef(42, ChatKind.PRIVATE, "Дмитро"),
                42, 5, "/start K7QXM2PA"));
    }

    @Test
    void supergroupCommandAndCallbackAndInlineQuery() throws Exception {
        ChatMessage group = (ChatMessage) map("""
                {"update_id":11,"message":{"message_id":6,"date":1760000000,"from":%s,
                 "chat":{"id":-1001234567890,"type":"supergroup","title":"4КІ1 — офіційний"},"text":"/today@scheduler_vtfk_bot"}}
                """.formatted(USER)).orElseThrow();
        assertThat(group.chat()).isEqualTo(new BotUpdate.ChatRef(-1001234567890L, ChatKind.GROUP, "4КІ1 — офіційний"));

        CallbackAction callback = (CallbackAction) map("""
                {"update_id":12,"callback_query":{"id":"cb-1","from":%s,"chat_instance":"77","data":"mg:c:4",
                 "message":{"message_id":7,"date":1760000000,"chat":{"id":42,"type":"private"},"text":"Оберіть курс:"}}}
                """.formatted(USER)).orElseThrow();
        assertThat(callback).isEqualTo(new CallbackAction(12, "cb-1", 42,
                new BotUpdate.ChatRef(42, ChatKind.PRIVATE, null), 7L, "mg:c:4"));

        InlineRequest inline = (InlineRequest) map("""
                {"update_id":13,"inline_query":{"id":"q-1","from":%s,"query":"4КІ1","offset":""}}
                """.formatted(USER)).orElseThrow();
        assertThat(inline).isEqualTo(new InlineRequest(13, "q-1", 42, "4КІ1"));
    }

    @Test
    void botRemovedFromGroupAndGroupUpgradedToSupergroup() throws Exception {
        MembershipChange removed = (MembershipChange) map("""
                {"update_id":14,"my_chat_member":{"chat":{"id":-555,"type":"group","title":"Чат 2КІ3"},"from":%s,
                 "date":1760000000,
                 "old_chat_member":{"status":"member","user":{"id":7,"is_bot":true,"first_name":"bot"}},
                 "new_chat_member":{"status":"left","user":{"id":7,"is_bot":true,"first_name":"bot"}}}}
                """.formatted(USER)).orElseThrow();
        assertThat(removed.botIsMember()).isFalse();
        assertThat(removed.chat().id()).isEqualTo(-555);

        ChatMigration migration = (ChatMigration) map("""
                {"update_id":15,"message":{"message_id":9,"date":1760000000,"from":%s,
                 "chat":{"id":-555,"type":"group","title":"Чат 2КІ3"},"migrate_to_chat_id":-1009998887776}}
                """.formatted(USER)).orElseThrow();
        assertThat(migration).isEqualTo(new ChatMigration(15, -555, -1009998887776L));
    }

    @Test
    void stickersAndOtherUpdatesAreDropped() throws Exception {
        assertThat(map("""
                {"update_id":16,"message":{"message_id":10,"date":1760000000,"from":%s,
                 "chat":{"id":42,"type":"private"},"sticker":{"file_id":"x","file_unique_id":"y","type":"regular",
                 "width":1,"height":1,"is_animated":false,"is_video":false}}}
                """.formatted(USER))).isEmpty();
        assertThat(map("{\"update_id\":17,\"edited_message\":{\"message_id\":1,\"date\":1,"
                + "\"chat\":{\"id\":42,\"type\":\"private\"},\"text\":\"x\"}}")).isEmpty();
    }

    private Optional<BotUpdate> map(String json) throws Exception {
        return UpdateMapper.map(telegramJson.readValue(json, Update.class));
    }
}
