package ua.vtc.rozklad.notification.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.TelegramUrl;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineButton;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineKeyboard;
import ua.vtc.rozklad.notification.telegram.TelegramApi.SentMessage;

/** The adapter against a fake Bot API: requests, results and every failure kind the worker relies on. */
class OkHttpTelegramApiTest {

    private static final String TOKEN = "123456:SECRET-token-value";

    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private OkHttpTelegramApi api;

    @BeforeEach
    void startFakeTelegram() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String method = exchange.getRequestURI().getPath().replaceFirst(".*/", "").toLowerCase();
            requests.add(method + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responses.getOrDefault(method, "{\"ok\":true,\"result\":true}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statuses.getOrDefault(method, 200), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        api = api(server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void sendsHtmlWithInlineKeyboardAndReturnsTheMessageId() {
        responses.put("sendmessage", "{\"ok\":true,\"result\":{\"message_id\":77,\"date\":1760000000,"
                + "\"chat\":{\"id\":42,\"type\":\"private\"},\"text\":\"x\"}}");
        SentMessage sent = api.sendText(42, "<b>Привіт</b>", InlineKeyboard.rows(List.of(
                InlineButton.callback("✅ Підтверджую", "sr:c:1"))));
        assertThat(sent).isEqualTo(new SentMessage(42, 77));
        assertThat(requests.getFirst()).startsWith("sendmessage").contains("\"parse_mode\":\"html\"")
                .contains("\"callback_data\":\"sr:c:1\"").contains("\"chat_id\":\"42\"");
    }

    @Test
    void identifiesTheBot() {
        responses.put("getme", "{\"ok\":true,\"result\":{\"id\":7,\"is_bot\":true,\"first_name\":\"Розклад\","
                + "\"username\":\"scheduler_vtfk_bot\"}}");
        assertThat(api.getMe()).isEqualTo(new TelegramApi.BotIdentity(7, "scheduler_vtfk_bot", "Розклад"));
    }

    @Test
    void tooManyRequestsCarriesRetryAfter() {
        statuses.put("sendmessage", 429);
        responses.put("sendmessage", "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests: "
                + "retry after 7\",\"parameters\":{\"retry_after\":7}}");
        assertThatThrownBy(() -> api.sendText(42, "x", null))
                .isInstanceOfSatisfying(TelegramApiFailure.class, failure -> {
                    assertThat(failure.isRateLimited()).isTrue();
                    assertThat(failure.retryAfterSeconds()).isEqualTo(7);
                    assertThat(failure.getMessage()).doesNotContain(TOKEN);
                });
    }

    @Test
    void editOfAnUnchangedMessageIsRecognisable() {
        statuses.put("editmessagetext", 400);
        responses.put("editmessagetext", "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: message is "
                + "not modified: specified new message content and reply markup are exactly the same\"}");
        assertThatThrownBy(() -> api.editText(42, 77, "x", null))
                .isInstanceOfSatisfying(TelegramApiFailure.class,
                        failure -> assertThat(failure.isMessageNotModified()).isTrue());
        assertThat(requests.getFirst()).doesNotContain("reply_markup");
    }

    @Test
    void supergroupMigrationAndBlockedBot() {
        statuses.put("sendmessage", 400);
        responses.put("sendmessage", "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: group chat was "
                + "upgraded to a supergroup chat\",\"parameters\":{\"migrate_to_chat_id\":-1001234}}");
        assertThatThrownBy(() -> api.sendText(-55, "x", null)).isInstanceOfSatisfying(TelegramApiFailure.class,
                failure -> assertThat(failure.migrateToChatId()).isEqualTo(-1001234L));

        statuses.put("sendmessage", 403);
        responses.put("sendmessage", "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot was blocked by "
                + "the user\"}");
        assertThatThrownBy(() -> api.sendText(42, "x", null)).isInstanceOfSatisfying(TelegramApiFailure.class,
                failure -> assertThat(failure.isForbidden()).isTrue());
    }

    @Test
    void unreachableTelegramIsANetworkFailureWithoutTheToken() throws IOException {
        int closedPort;
        try (var socket = new java.net.ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        OkHttpTelegramApi unreachable = api(closedPort);
        assertThatThrownBy(() -> unreachable.sendText(42, "x", null))
                .isInstanceOfSatisfying(TelegramApiFailure.class, failure -> {
                    assertThat(failure.isNetwork()).isTrue();
                    assertThat(failure.getMessage()).doesNotContain(TOKEN).doesNotContain("SECRET");
                });
    }

    private static OkHttpTelegramApi api(int port) {
        TelegramUrl url = TelegramUrl.builder().schema("http").host("127.0.0.1").port(port).testServer(false).build();
        return new OkHttpTelegramApi(new OkHttpTelegramClient(new OkHttpClient(), TOKEN, url), TOKEN);
    }
}
