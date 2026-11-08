package ua.vtc.rozklad.notification.support;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineButton;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineKeyboard;
import ua.vtc.rozklad.notification.telegram.TelegramApi;
import ua.vtc.rozklad.notification.telegram.TelegramApiFailure;

/**
 * In-memory Telegram: records every call (texts, keyboards, edits, callback and inline answers),
 * returns increasing message ids and can fail the next message calls on demand.
 */
public class FakeTelegramClient implements TelegramApi {

    public sealed interface Call permits Sent, Photo, Edit, CallbackAnswer, InlineAnswer, Other {
    }

    public record Sent(long chatId, long messageId, String text, ReplyMarkup markup) implements Call {

        /** Texts of all inline buttons, row by row flattened. */
        public List<String> buttons() {
            return markup instanceof InlineKeyboard keyboard
                    ? keyboard.rows().stream().flatMap(List::stream).map(InlineButton::text).toList()
                    : List.of();
        }

        public Optional<String> callbackOf(String buttonText) {
            return markup instanceof InlineKeyboard keyboard ? callback(keyboard, buttonText) : Optional.empty();
        }
    }

    public record Photo(long chatId, long messageId, byte[] png, String fileName, String caption) implements Call {
    }

    public record Edit(long chatId, long messageId, String text, InlineKeyboard keyboard) implements Call {

        public List<String> buttons() {
            return keyboard == null ? List.of()
                    : keyboard.rows().stream().flatMap(List::stream).map(InlineButton::text).toList();
        }

        public Optional<String> callbackOf(String buttonText) {
            return keyboard == null ? Optional.empty() : callback(keyboard, buttonText);
        }
    }

    public record CallbackAnswer(String callbackId, String text, boolean alert) implements Call {
    }

    public record InlineAnswer(String queryId, List<InlineArticle> articles) implements Call {
    }

    public record Other(String method) implements Call {
    }

    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedDeque<TelegramApiFailure> failures = new ConcurrentLinkedDeque<>();
    private final AtomicLong nextMessageId = new AtomicLong(100);
    private final Map<String, MemberStatus> members = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<TelegramApiFailure> memberFailures = new ConcurrentLinkedDeque<>();

    /** {@code getChatMember} answers this status for the user in the chat (default: an ordinary member). */
    public void setMemberStatus(long chatId, long userId, MemberStatus status) {
        members.put(chatId + ":" + userId, status);
    }

    /** The next {@code getChatMember} throws this failure. */
    public void failNextMemberStatus(TelegramApiFailure failure) {
        memberFailures.add(failure);
    }

    /** How many {@code getChatMember} calls were made (the admin check is cached). */
    public long memberStatusCalls() {
        return calls.stream().filter(call -> call instanceof Other other && other.method().startsWith("getChatMember"))
                .count();
    }

    /** The next message call (send/photo/edit) throws this failure. */
    public void failNext(TelegramApiFailure failure) {
        failures.add(failure);
    }

    public void reset() {
        calls.clear();
        failures.clear();
        members.clear();
        memberFailures.clear();
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public List<Sent> sentTo(long chatId) {
        return calls.stream().filter(Sent.class::isInstance).map(Sent.class::cast)
                .filter(sent -> sent.chatId() == chatId).toList();
    }

    public Sent lastSentTo(long chatId) {
        List<Sent> sent = sentTo(chatId);
        if (sent.isEmpty()) {
            throw new AssertionError("Nothing was sent to chat " + chatId + "; calls: " + calls);
        }
        return sent.getLast();
    }

    public List<Photo> photosTo(long chatId) {
        return calls.stream().filter(Photo.class::isInstance).map(Photo.class::cast)
                .filter(photo -> photo.chatId() == chatId).toList();
    }

    public List<Edit> editsIn(long chatId) {
        return calls.stream().filter(Edit.class::isInstance).map(Edit.class::cast)
                .filter(edit -> edit.chatId() == chatId).toList();
    }

    public Edit lastEditIn(long chatId) {
        List<Edit> edits = editsIn(chatId);
        if (edits.isEmpty()) {
            throw new AssertionError("No edits in chat " + chatId + "; calls: " + calls);
        }
        return edits.getLast();
    }

    public List<CallbackAnswer> callbackAnswers() {
        return calls.stream().filter(CallbackAnswer.class::isInstance).map(CallbackAnswer.class::cast).toList();
    }

    public CallbackAnswer lastCallbackAnswer() {
        return callbackAnswers().getLast();
    }

    public List<InlineAnswer> inlineAnswers() {
        return calls.stream().filter(InlineAnswer.class::isInstance).map(InlineAnswer.class::cast).toList();
    }

    /** Messages (texts and photos) sent to any chat. */
    public long messageCount() {
        return calls.stream().filter(call -> call instanceof Sent || call instanceof Photo).count();
    }

    @Override
    public BotIdentity getMe() {
        calls.add(new Other("getMe"));
        return new BotIdentity(7, "scheduler_vtfk_bot", "Розклад ВТФК");
    }

    @Override
    public SentMessage sendText(long chatId, String html, ReplyMarkup markup) {
        failIfAsked();
        long id = nextMessageId.incrementAndGet();
        calls.add(new Sent(chatId, id, html, markup));
        return new SentMessage(chatId, id);
    }

    @Override
    public SentMessage sendPhoto(long chatId, byte[] png, String fileName, String captionHtml) {
        failIfAsked();
        long id = nextMessageId.incrementAndGet();
        calls.add(new Photo(chatId, id, png, fileName, captionHtml));
        return new SentMessage(chatId, id);
    }

    @Override
    public void editText(long chatId, long messageId, String html, InlineKeyboard keyboard) {
        failIfAsked();
        calls.add(new Edit(chatId, messageId, html, keyboard));
    }

    @Override
    public void answerCallback(String callbackQueryId, String text, boolean alert) {
        calls.add(new CallbackAnswer(callbackQueryId, text, alert));
    }

    @Override
    public void answerInlineQuery(String inlineQueryId, List<InlineArticle> articles, int cacheSeconds) {
        calls.add(new InlineAnswer(inlineQueryId, List.copyOf(articles)));
    }

    @Override
    public void setCommands(CommandScope scope, List<BotCommandSpec> commands) {
        calls.add(new Other("setCommands " + scope));
    }

    @Override
    public MemberStatus memberStatus(long chatId, long userId) {
        calls.add(new Other("getChatMember " + chatId + " " + userId));
        TelegramApiFailure failure = memberFailures.poll();
        if (failure != null) {
            throw failure;
        }
        return members.getOrDefault(chatId + ":" + userId, MemberStatus.MEMBER);
    }

    @Override
    public void deleteWebhook() {
        calls.add(new Other("deleteWebhook"));
    }

    @Override
    public void setWebhook(String url, String secretToken, List<String> allowedUpdates) {
        calls.add(new Other("setWebhook"));
    }

    private void failIfAsked() {
        TelegramApiFailure failure = failures.poll();
        if (failure != null) {
            throw failure;
        }
    }

    private static Optional<String> callback(InlineKeyboard keyboard, String buttonText) {
        return keyboard.rows().stream().flatMap(List::stream)
                .filter(button -> button.text().equals(buttonText))
                .map(InlineButton::callbackData)
                .findFirst();
    }
}
