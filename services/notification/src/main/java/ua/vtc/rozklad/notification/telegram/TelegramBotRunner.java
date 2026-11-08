package ua.vtc.rozklad.notification.telegram;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.DefaultGetUpdatesGenerator;
import org.telegram.telegrambots.meta.TelegramUrl;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ua.vtc.rozklad.notification.binding.ChatBindings;
import ua.vtc.rozklad.notification.bot.BotUpdate;
import ua.vtc.rozklad.notification.bot.UpdateDispatcher;
import ua.vtc.rozklad.notification.config.TelegramProperties;
import ua.vtc.rozklad.notification.telegram.TelegramApi.BotCommandSpec;
import ua.vtc.rozklad.notification.telegram.TelegramApi.CommandScope;

/**
 * Starts the bot after the application is up: registers the owner chat, checks the token
 * ({@code getMe}), sets the command menus and starts receiving updates — long polling (default;
 * run only ONE instance per token) or a webhook. Telegram calls run on a separate thread, so a slow
 * Telegram never delays the service start.
 */
@Component
public class TelegramBotRunner implements SmartLifecycle {

    static final List<BotCommandSpec> PRIVATE_COMMANDS = List.of(
            new BotCommandSpec("today", "Розклад на сьогодні"),
            new BotCommandSpec("tomorrow", "Розклад на завтра"),
            new BotCommandSpec("group", "Моя група"),
            new BotCommandSpec("find", "Знайти групу, викладача чи аудиторію"),
            new BotCommandSpec("bells", "Розклад дзвінків"),
            new BotCommandSpec("parity", "Чисельник чи знаменник"),
            new BotCommandSpec("mytomorrow", "Мої пари завтра (викладачам)"),
            new BotCommandSpec("absence", "Повідомити про відсутність (викладачам)"),
            new BotCommandSpec("help", "Що вміє бот"));
    static final List<BotCommandSpec> GROUP_COMMANDS = List.of(
            new BotCommandSpec("today", "Розклад групи на сьогодні"),
            new BotCommandSpec("tomorrow", "Розклад групи на завтра"),
            new BotCommandSpec("status", "Що отримує цей чат"),
            new BotCommandSpec("rozklad_on", "Щоранку надсилати розклад групи"),
            new BotCommandSpec("rozklad_off", "Не надсилати ранковий розклад"),
            new BotCommandSpec("bind", "Прив’язати чат до групи (код з веб-панелі)"),
            new BotCommandSpec("help", "Команди бота"));

    private static final Logger log = LoggerFactory.getLogger(TelegramBotRunner.class);

    private final TelegramProperties telegram;
    private final TelegramApi api;
    private final ChatBindings bindings;
    private final UpdateDispatcher dispatcher;
    private PerChatExecutor executor;
    private TelegramBotsLongPollingApplication polling;
    private volatile boolean running;
    private volatile boolean receiving;

    public TelegramBotRunner(TelegramProperties telegram, TelegramApi api, ChatBindings bindings,
                             UpdateDispatcher dispatcher) {
        this.telegram = telegram;
        this.api = api;
        this.bindings = bindings;
        this.dispatcher = dispatcher;
    }

    @Override
    public synchronized void start() {
        running = true;
        if (telegram.hasOwnerChat()) {
            bindings.ensureOwnerAdminChat(telegram.ownerChatId());
        }
        if (!telegram.enabled()) {
            log.info("Telegram bot is disabled: TELEGRAM_BOT_TOKEN is empty (documents and REST still work)");
            return;
        }
        executor = new PerChatExecutor();
        Thread.ofVirtual().name("telegram-startup").start(this::connect);
    }

    private void connect() {
        try {
            TelegramApi.BotIdentity me = api.getMe();
            log.info("Telegram bot @{} connected (mode {}, access {})", me.username(), telegram.mode(),
                    telegram.access());
        } catch (TelegramApiFailure failure) {
            log.warn("Telegram getMe failed: {}", failure.getMessage());
        }
        registerCommands();
        switch (telegram.mode()) {
            case POLLING -> startPolling();
            case WEBHOOK -> registerWebhook();
        }
    }

    private void registerCommands() {
        try {
            api.setCommands(CommandScope.PRIVATE_CHATS, PRIVATE_COMMANDS);
            api.setCommands(CommandScope.GROUP_CHATS, GROUP_COMMANDS);
        } catch (TelegramApiFailure failure) {
            log.warn("Bot commands not registered: {}", failure.getMessage());
        }
    }

    private synchronized void startPolling() {
        if (!running) {
            return;
        }
        try {
            // активний вебхук блокує getUpdates (409), тож у режимі POLLING його прибираємо
            api.deleteWebhook();
        } catch (TelegramApiFailure failure) {
            log.warn("deleteWebhook failed: {}", failure.getMessage());
        }
        try {
            polling = new TelegramBotsLongPollingApplication();
            polling.registerBot(telegram.botToken(), () -> TelegramUrl.DEFAULT_URL,
                    new DefaultGetUpdatesGenerator(UpdateMapper.ALLOWED_UPDATES), this::consume);
            if (!polling.isRunning()) {
                polling.start();
            }
            receiving = true;
            log.info("Telegram long polling started");
        } catch (TelegramApiException e) {
            log.error("Telegram long polling could not start: {}", e.getMessage());
        }
    }

    private void registerWebhook() {
        if (blank(telegram.webhookUrl())) {
            log.warn("TELEGRAM_MODE=WEBHOOK without TELEGRAM_WEBHOOK_URL: the bot sends, but receives no updates");
            return;
        }
        if (blank(telegram.webhookSecret())) {
            log.error("TELEGRAM_WEBHOOK_SECRET is empty: refusing to register an unauthenticated webhook");
            return;
        }
        try {
            api.setWebhook(telegram.webhookUrl(), telegram.webhookSecret(), UpdateMapper.ALLOWED_UPDATES);
            receiving = true;
            log.info("Telegram webhook registered");
        } catch (TelegramApiFailure failure) {
            log.error("Telegram webhook registration failed: {}", failure.getMessage());
        }
    }

    /** Long polling batch (and webhook updates through {@link #submit}). */
    void consume(List<Update> updates) {
        for (Update update : updates) {
            UpdateMapper.map(update).ifPresent(this::submit);
        }
    }

    public void submit(BotUpdate update) {
        PerChatExecutor current = executor;
        if (current == null) {
            log.warn("Update {} dropped: the bot is not running", update.updateId());
            return;
        }
        current.submit(update.orderingKey(), () -> dispatcher.dispatch(update));
    }

    /** Updates are being received (polling session up or webhook registered). */
    public boolean isReceiving() {
        return receiving;
    }

    @Override
    public synchronized void stop() {
        running = false;
        receiving = false;
        if (polling != null) {
            try {
                polling.close();
                log.info("Telegram long polling stopped");
            } catch (Exception e) {
                log.warn("Telegram long polling did not stop cleanly: {}", e.getMessage());
            }
            polling = null;
        }
        if (executor != null) {
            executor.close();
            executor = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
