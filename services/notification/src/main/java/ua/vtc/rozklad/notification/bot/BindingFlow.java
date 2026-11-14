package ua.vtc.rozklad.notification.bot;

import static ua.vtc.rozklad.notification.text.Html.b;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.binding.BindingAttempts;
import ua.vtc.rozklad.notification.binding.BindingCodes;
import ua.vtc.rozklad.notification.binding.BindingCodes.Redemption;
import ua.vtc.rozklad.notification.binding.BindingTarget;
import ua.vtc.rozklad.notification.binding.BindingType;
import ua.vtc.rozklad.notification.binding.ChatBinding;
import ua.vtc.rozklad.notification.binding.ChatBindings;
import ua.vtc.rozklad.notification.bot.BotUpdate.ChatMessage;
import ua.vtc.rozklad.notification.config.BotProperties;
import ua.vtc.rozklad.notification.config.TelegramProperties;
import ua.vtc.rozklad.notification.shared.ChatIds;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup;
import ua.vtc.rozklad.notification.telegram.TelegramApiFailure;

/**
 * {@code /start <code>} (deep link in a private chat or {@code ?startgroup=}) and
 * {@code /bind <code>} (group chat). An unknown chat gets an answer only when it presents a code
 * that really exists — the bot never writes to chats that were not bound through the system.
 * Wrong codes are counted per chat and user ({@link BindingAttempts}): after five within 15 minutes codes are
 * not looked up at all, so nobody can find a code by sending one guess after another.
 * In a group chat any member may bind a new chat to its group, but only a chat admin may make the chat a service
 * chat (ADMIN_CHAT code) or apply a code to a chat that is already bound (move it to another group): a code
 * that leaked into a student chat must not redirect it. A teacher whose chat is taken over by a newer code is
 * told so in the old chat.
 */
@Component
public class BindingFlow {

    static final String ADMINS_ONLY = "Застосувати цей код у груповому чаті може лише адміністратор чату.";
    static final String ADMIN_CHECK_FAILED = "Не вдалося перевірити, чи ви адміністратор чату. Спробуйте за хвилину.";

    private static final Logger log = LoggerFactory.getLogger(BindingFlow.class);

    private final BindingCodes codes;
    private final BindingAttempts attempts;
    private final ChatAccess access;
    private final ChatBindings bindings;
    private final ChatAdmins admins;
    private final BotReplies replies;
    private final TelegramProperties telegram;
    private final BotProperties bot;

    public BindingFlow(BindingCodes codes, BindingAttempts attempts, ChatAccess access, ChatBindings bindings,
                       ChatAdmins admins, BotReplies replies, TelegramProperties telegram, BotProperties bot) {
        this.codes = codes;
        this.attempts = attempts;
        this.access = access;
        this.bindings = bindings;
        this.admins = admins;
        this.replies = replies;
        this.telegram = telegram;
        this.bot = bot;
    }

    public void redeem(ChatMessage message, String code) {
        long chatId = message.chat().id();
        boolean knownBefore = access.mayTalkTo(chatId);
        // анонімний адміністратор пише як GroupAnonymousBot — цей id спільний для всіх груп, тож рахуємо лише чат
        Long userId = message.fromAnonymousAdmin() ? null : message.fromUserId();
        BindingAttempts.Check check = attempts.check(chatId, userId);
        if (check != BindingAttempts.Check.ALLOWED) {
            // незнайомому чату не відповідаємо, як і на вигаданий код
            if (knownBefore) {
                replies.send(chatId, check == BindingAttempts.Check.BLOCKED
                        ? "Забагато невдалих спроб. Спробуйте ще раз через " + bot.bindAttemptsWindow().toMinutes()
                        + " хв."
                        : "Не вдалося перевірити код. Спробуйте за кілька хвилин.", null);
            }
            return;
        }
        if (message.chat().isGroup() && !mayRedeemInGroup(message, code)) {
            return;
        }
        Redemption redemption = codes.redeem(code, message.chat());
        switch (redemption) {
            case Redemption.Bound bound -> {
                replies.send(chatId, boundText(bound.target(), bound.binding()), keyboard(bound.target()));
                if (bound.displacedChatId() != null) {
                    long displaced = bound.displacedChatId();
                    log.info("Teacher chat {} replaced by chat {}", ChatIds.mask(displaced), ChatIds.mask(chatId));
                    replies.send(displaced, displacedText(bound.target()),
                            bindings.isKnownChat(displaced) ? BotTexts.mainMenu(false) : null);
                }
            }
            case Redemption.Rejected rejected -> {
                attempts.recordFailure(chatId, userId);
                if (!knownBefore && rejected.reason() == BindingCodes.RejectReason.UNKNOWN) {
                    log.info("Ignored an unknown binding code from unbound chat {}", ChatIds.mask(chatId));
                    return;
                }
                replies.send(chatId, rejectedText(rejected), null);
            }
        }
    }

    /**
     * A non-admin may apply only a group code to a group chat that is not bound yet. An unknown, used or expired
     * code is left to {@link BindingCodes#redeem}, which rejects it and counts the failure.
     */
    private boolean mayRedeemInGroup(ChatMessage message, String code) {
        Optional<BindingType> type = codes.redeemableType(code);
        long chatId = message.chat().id();
        boolean needsAdmin = type.isPresent() && (type.get() == BindingType.ADMIN_CHAT
                || type.get() == BindingType.GROUP_CHAT && bindings.isKnownChat(chatId));
        if (!needsAdmin) {
            return true;
        }
        try {
            if (admins.isAdmin(message)) {
                return true;
            }
            log.info("{} code in chat {} refused: the author is not a chat admin", type.get(), ChatIds.mask(chatId));
            replies.send(chatId, ADMINS_ONLY, null);
        } catch (TelegramApiFailure e) {
            log.warn("Admin check in chat {} failed: {}", ChatIds.mask(chatId), e.getMessage());
            replies.send(chatId, ADMIN_CHECK_FAILED, null);
        }
        return false;
    }

    /** To the teacher's previous chat after a newer code moved his requests elsewhere. */
    static String displacedText(BindingTarget target) {
        return "⚠️ Telegram викладача " + b(target.label()) + " щойно підключили в іншому чаті, тож прохання "
                + "про заміни сюди більше не надходитимуть.\n"
                + "Якщо це зробили не ви, підключіть Telegram знову в кабінеті на сайті й повідомте диспетчера.";
    }

    static String boundText(BindingTarget target, ChatBinding binding) {
        return switch (target.type()) {
            case TEACHER -> "✅ Вітаю, " + b(target.label()) + "!\n"
                    + "Тепер сюди приходитимуть прохання про заміни — відповідати можна однією кнопкою.\n"
                    + "Тут же можна переглянути свої пари й повідомити про відсутність.";
            case SUBSCRIBER -> "✅ Готово! Ви підписані на розклад групи " + b(target.label()) + ".\n"
                    + "Щоранку о 7:30 у навчальні дні я надсилатиму розклад на день, "
                    + "а «📅 Сьогодні» і «📆 Завтра» покажуть його будь-коли.";
            case GROUP_CHAT -> "✅ Чат прив’язано до групи " + b(target.label()) + ".\n"
                    + "Після кожного розпорядження сюди надходитимуть зміни для групи та зображення розпорядження.\n"
                    + (binding.isDigestEnabled()
                    ? "Ранковий розклад о 7:30 увімкнено. Вимкнути: /rozklad_off (лише адміністратор чату).\n"
                    : "Ранковий розклад о 7:30 вимкнено. Увімкнути: /rozklad_on (лише адміністратор чату).\n")
                    + "Команди: /today — розклад на сьогодні, /tomorrow — на завтра, /status — що отримує чат.";
            case ADMIN_CHAT -> "✅ Цей чат тепер службовий: сюди надходитимуть відсутності викладачів, "
                    + "відмови від замін і зведення опублікованих розпоряджень.";
        };
    }

    private ReplyMarkup keyboard(BindingTarget target) {
        return switch (target.type()) {
            case TEACHER -> BotTexts.mainMenu(true);
            case SUBSCRIBER -> BotTexts.mainMenu(false);
            case GROUP_CHAT, ADMIN_CHAT -> null;
        };
    }

    private String rejectedText(Redemption.Rejected rejected) {
        return switch (rejected.reason()) {
            case UNKNOWN -> "Код не знайдено. Перевірте його або попросіть новий у веб-панелі.";
            case USED -> "Цей код уже використано. Попросіть новий у веб-панелі (Telegram → Прив’язки).";
            case EXPIRED -> "Термін дії коду минув. Попросіть новий у веб-панелі.";
            case WRONG_CHAT -> rejected.codeType() == BindingType.GROUP_CHAT
                    ? "Цей код — для чату групи. Додайте бота до групового чату й надішліть там: /bind КОД"
                    : "Цей код — для особистого чату з ботом: відкрийте @" + telegram.botUsername()
                    + " і натисніть «Почати» за посиланням із веб-панелі.";
            case BURNED -> "Цей код — для особистого чату, а його надіслали в груповий. Щоб ніхто інший "
                    + "ним не скористався, код анульовано. Створіть новий у веб-панелі.";
        };
    }
}
