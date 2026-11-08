package ua.vtc.rozklad.notification.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import ua.vtc.rozklad.notification.binding.BindingCodes;
import ua.vtc.rozklad.notification.binding.BindingTarget;
import ua.vtc.rozklad.notification.binding.BindingType;
import ua.vtc.rozklad.notification.binding.ChatBinding;
import ua.vtc.rozklad.notification.binding.ChatBindings;
import ua.vtc.rozklad.notification.bot.BotUpdate.CallbackAction;
import ua.vtc.rozklad.notification.bot.BotUpdate.ChatKind;
import ua.vtc.rozklad.notification.bot.BotUpdate.ChatMessage;
import ua.vtc.rozklad.notification.bot.BotUpdate.ChatRef;
import ua.vtc.rozklad.notification.bot.UpdateDispatcher;
import ua.vtc.rozklad.notification.config.KeycloakRealmRoleConverter;
import ua.vtc.rozklad.notification.delivery.DeliveryWorker;
import ua.vtc.rozklad.notification.documents.DocumentPrerenderer;

/**
 * Base of integration tests: the whole service on PostgreSQL, RabbitMQ and Redis in Docker, with
 * Telegram, core, importer, LibreOffice and the clock replaced by test doubles. Updates are
 * dispatched synchronously and the delivery queue is drained explicitly, so tests are deterministic.
 * All subclasses share one Spring context.
 */
@SpringBootTest(properties = {
        "app.telegram.bot-token=123456:integration-test-token",
        "app.telegram.owner-chat-id=" + IntegrationTest.OWNER_CHAT,
        "app.telegram.mode=WEBHOOK",
        "app.telegram.webhook-secret=" + IntegrationTest.WEBHOOK_SECRET,
        "app.telegram.access=BOUND_ONLY",
        "app.scheduling.enabled=false",
        "app.delivery.worker-enabled=false",
        "app.delivery.global-per-second=100000",
        "app.delivery.group-chat-per-minute=1000000",
        "app.delivery.private-chat-per-second=100000",
        "app.delivery.burst=100000",
        "app.documents.cache-dir=target/it-documents",
        "spring.rabbitmq.listener.simple.retry.initial-interval=200ms",
        "spring.rabbitmq.listener.simple.retry.max-interval=500ms"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestDoubles.class})
public abstract class IntegrationTest {

    protected static final long OWNER_CHAT = 1000;
    protected static final String WEBHOOK_SECRET = "it-webhook-secret";

    private static final AtomicLong IDS = new AtomicLong(1);

    @Autowired
    protected FakeTelegramClient telegram;
    @Autowired
    protected FakeCoreClient core;
    @Autowired
    protected FakeImporterClient importer;
    @Autowired
    protected FakePdfConverter pdfConverter;
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected UpdateDispatcher dispatcher;
    @Autowired
    protected DeliveryWorker worker;
    @Autowired
    protected ChatBindings bindings;
    @Autowired
    protected BindingCodes codes;
    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private DocumentPrerenderer prerenderer;

    @BeforeEach
    void resetState() throws IOException {
        prerenderer.awaitIdle(Duration.ofSeconds(10));
        jdbc.execute("TRUNCATE chat_binding, binding_code, outbound_message, published_order, substitution_message, "
                + "substitution_request, processed_event");
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        Path cache = Path.of("target", "it-documents", "orders");
        if (Files.exists(cache)) {
            try (Stream<Path> files = Files.walk(cache)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
        telegram.reset();
        core.reset();
        importer.reset();
        pdfConverter.reset();
        clock.reset();
    }

    /** Drops the Redis read-through cache (meta, schedules) after a fixture of the fake core changed. */
    protected void flushCaches() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }

    protected static ChatRef privateChat(long chatId) {
        return new ChatRef(chatId, ChatKind.PRIVATE, null);
    }

    protected static ChatRef groupChat(long chatId, String title) {
        return new ChatRef(chatId, ChatKind.GROUP, title);
    }

    /** A user types {@code text} in his private chat. */
    protected void say(long chatId, String text) {
        dispatcher.dispatch(new ChatMessage(IDS.incrementAndGet(), privateChat(chatId), chatId, IDS.incrementAndGet(),
                text));
    }

    protected void sayInGroup(long chatId, String text) {
        dispatcher.dispatch(new ChatMessage(IDS.incrementAndGet(), groupChat(chatId, "Чат групи"), 42,
                IDS.incrementAndGet(), text));
    }

    /** A user presses an inline button under message {@code messageId}. */
    protected void press(long chatId, long messageId, String data) {
        ChatRef chat = chatId > 0 ? privateChat(chatId) : groupChat(chatId, "Чат групи");
        dispatcher.dispatch(new CallbackAction(IDS.incrementAndGet(), "cb-" + IDS.incrementAndGet(), chatId > 0 ? chatId
                : 42, chat, messageId, data));
    }

    protected ChatBinding owner() {
        bindings.ensureOwnerAdminChat(OWNER_CHAT);
        return bindings.find(OWNER_CHAT, BindingType.ADMIN_CHAT).orElseThrow();
    }

    protected ChatBinding teacherChat(long chatId, UUID teacherId, String name) {
        return bindings.bind(BindingTarget.teacher(teacherId, name), privateChat(chatId));
    }

    protected ChatBinding groupChatOf(long chatId, UUID groupId, String code) {
        return bindings.bind(BindingTarget.group(BindingType.GROUP_CHAT, groupId, code), groupChat(chatId, "Чат " + code));
    }

    protected ChatBinding subscriber(long chatId, UUID groupId, String code) {
        return bindings.bind(BindingTarget.group(BindingType.SUBSCRIBER, groupId, code), privateChat(chatId));
    }

    /** Raw code (no dash) as it comes from a deep link. */
    protected String issue(BindingTarget target) {
        return codes.issue(target, "admin").code().replace("-", "");
    }

    protected static JwtRequestPostProcessor admin() {
        return token("ADMIN", "admin", "admin-token");
    }

    protected static JwtRequestPostProcessor teacher(String tokenValue) {
        return token("TEACHER", "nepyivoda", tokenValue);
    }

    protected static JwtRequestPostProcessor token(String role, String username, String tokenValue) {
        return jwt().jwt(token -> token.tokenValue(tokenValue)
                        .claim("realm_access", Map.of("roles", List.of(role)))
                        .claim("preferred_username", username))
                .authorities(new KeycloakRealmRoleConverter());
    }

    /** Polls until {@code condition} holds (asynchronous consumers); fails after {@code timeout}. */
    protected static void await(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + timeout);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted", e);
            }
        }
    }
}
