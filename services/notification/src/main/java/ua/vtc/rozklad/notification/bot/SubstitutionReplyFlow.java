package ua.vtc.rozklad.notification.bot;

import java.util.UUID;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.ConfirmationDecision;
import ua.vtc.rozklad.notification.bot.BotUpdate.CallbackAction;
import ua.vtc.rozklad.notification.substitution.SubstitutionRequests;
import ua.vtc.rozklad.notification.substitution.SubstitutionRequests.ReplyOutcome;
import ua.vtc.rozklad.notification.substitution.SubstitutionTexts;

/** [✅ Підтверджую] / [❌ Не можу] under a substitution request: answer core, edit the message to the final state. */
@Component
public class SubstitutionReplyFlow {

    private final SubstitutionRequests substitutions;
    private final BotReplies replies;

    public SubstitutionReplyFlow(SubstitutionRequests substitutions, BotReplies replies) {
        this.substitutions = substitutions;
        this.replies = replies;
    }

    public void onCallback(CallbackAction action) {
        String data = action.data();
        ConfirmationDecision decision = data.startsWith(SubstitutionTexts.CONFIRM_PREFIX)
                ? ConfirmationDecision.CONFIRM
                : ConfirmationDecision.DECLINE;
        UUID changeId;
        try {
            changeId = UUID.fromString(data.substring(SubstitutionTexts.CONFIRM_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            replies.alert(action.callbackId(), BotTexts.STALE_BUTTON);
            return;
        }
        long chatId = action.chat().id();
        long messageId = action.messageId();
        ReplyOutcome outcome = substitutions.reply(changeId, chatId, messageId, decision);
        switch (outcome) {
            case ReplyOutcome.Done done -> {
                replies.edit(chatId, messageId, SubstitutionTexts.finalState(done.request(), done.status()), null);
                replies.answer(action.callbackId(), decision == ConfirmationDecision.CONFIRM
                        ? "Дякуємо! Заміну підтверджено ✅"
                        : "Відмову передано диспетчеру");
            }
            case ReplyOutcome.NotPending notPending -> {
                // відомий фінальний стан («Ви підтвердили», «Заміну скасовано») не затираємо «неактуальним»
                String finalText = notPending.request() == null ? null
                        : SubstitutionTexts.finalState(notPending.request(), notPending.request().getStatus());
                replies.edit(chatId, messageId,
                        finalText != null ? finalText : SubstitutionTexts.outdated(notPending.request()), null);
                replies.answer(action.callbackId(), "Цей запит уже неактуальний");
            }
            case ReplyOutcome.NotFound notFound -> {
                replies.edit(chatId, messageId, SubstitutionTexts.outdated(notFound.request()), null);
                replies.answer(action.callbackId(), "Заміну не знайдено — можливо, її скасовано");
            }
            case ReplyOutcome.WrongTeacher ignored ->
                    replies.alert(action.callbackId(), "Цей запит адресовано іншому викладачу.");
            case ReplyOutcome.NotTeacher ignored -> replies.alert(action.callbackId(),
                    "Щоб відповідати на запити, підключіть Telegram у кабінеті викладача.");
            case ReplyOutcome.Unavailable ignored -> replies.alert(action.callbackId(),
                    "Сервер розкладу зараз недоступний. Спробуйте ще раз за хвилину.");
            case ReplyOutcome.Rejected rejected -> replies.alert(action.callbackId(),
                    rejected.detail() == null || rejected.detail().isBlank()
                            ? "Сервер розкладу відхилив відповідь."
                            : rejected.detail());
        }
    }
}
