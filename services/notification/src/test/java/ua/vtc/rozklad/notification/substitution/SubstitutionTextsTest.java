package ua.vtc.rozklad.notification.substitution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.ConfirmationDecision;
import ua.vtc.rozklad.contracts.SubstitutionRequestMode;
import ua.vtc.rozklad.contracts.events.SubstitutionConfirmationRequested;
import ua.vtc.rozklad.notification.support.Order35;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineButton;

class SubstitutionTextsTest {

    private static final Instant NOW = Instant.parse("2026-10-18T15:30:00Z");
    private static final String NDKS = "Надійність, діагностика та експлуатація комп'ютерних систем і мереж";

    @Test
    void requestListsEverythingTheTeacherNeeds() {
        SubstitutionRequest request = SubstitutionRequest.from(event(1), NOW);
        assertThat(SubstitutionTexts.request(request, false)).isEqualTo("""
                🙋 <b>Прохання про заміну</b>

                📅 Понеділок, 19 жовтня
                🕘 2 пара · 09:40–10:40
                👥 4КІ1 (1 підгрупа)
                📘 Надійність, діагностика та експлуатація комп'ютерних систем і мереж
                🚪 Аудиторія 6
                👤 Замість: Кренцін М.Д.

                Будь ласка, дайте відповідь до 18.10 о 20:00.""");
        assertThat(SubstitutionTexts.request(request, true)).startsWith("⏰ <b>Нагадування: потрібна ваша відповідь</b>");
        assertThat(SubstitutionTexts.buttons(Order35.CHANGE_4KI1).rows().getFirst())
                .extracting(InlineButton::text, InlineButton::callbackData)
                .containsExactly(
                        Tuple.tuple("✅ Підтверджую", "sr:c:" + Order35.CHANGE_4KI1),
                        Tuple.tuple("❌ Не можу", "sr:d:" + Order35.CHANGE_4KI1));
    }

    @Test
    void noticeOfAnotherDisciplineSaysWhatItReplaces() {
        SubstitutionRequest request = SubstitutionRequest.from(notice("MUTUAL"), NOW);
        assertThat(request.getStatus()).isEqualTo(ChangeStatus.CONFIRMED);
        assertThat(request.isAwaitingAnswer()).isTrue();
        assertThat(SubstitutionTexts.notice(request)).isEqualTo("""
                📌 <b>Вам поставили заміну</b>

                📅 Понеділок, 19 жовтня
                🕘 2 пара · 09:40–10:40
                👥 4КІ1
                📘 Надійність, діагностика та експлуатація комп'ютерних систем і мереж
                🔁 Замість «Сучасні технології проєктування» (Кренцін М.Д.) (взаємна заміна)
                🚪 Аудиторія 6

                Якщо не можете, натисніть «Не можу» — диспетчер обере інший варіант.""");
        assertThat(SubstitutionTexts.notice(SubstitutionRequest.from(notice("ABSENT"), NOW)))
                .contains("🔁 Замість «Сучасні технології проєктування» (Кренцін М.Д.)\n")
                .doesNotContain("взаємна", "👤 Замість");
        assertThat(SubstitutionTexts.noticeButtons(Order35.CHANGE_4KI1).rows().getFirst())
                .extracting(InlineButton::text, InlineButton::callbackData)
                .containsExactly(
                        Tuple.tuple("👍 Добре", "sr:c:" + Order35.CHANGE_4KI1),
                        Tuple.tuple("❌ Не можу", "sr:d:" + Order35.CHANGE_4KI1));
    }

    @Test
    void requestOfAnotherDisciplineAlsoSaysWhatItReplaces() {
        SubstitutionConfirmationRequested event = new SubstitutionConfirmationRequested(UUID.randomUUID(), NOW, 1,
                Order35.CHANGE_4KI1, Order35.TEACHER_NEPYIVODA, Order35.DATE, 2, null, null, "4КІ1", 0, NDKS,
                "Кренцін М.Д.", null, Instant.parse("2026-10-18T17:00:00Z"), SubstitutionRequestMode.CONFIRM,
                ChangeKind.REPLACE_DISCIPLINE, "Сучасні технології проєктування", "ABSENT");
        assertThat(SubstitutionTexts.request(SubstitutionRequest.from(event, NOW), false))
                .startsWith("🙋 <b>Прохання про заміну</b>")
                .contains("🔁 Замість «Сучасні технології проєктування» (Кренцін М.Д.)")
                .endsWith("Будь ласка, дайте відповідь до 18.10 о 20:00.");
    }

    @Test
    void noticeStatesFollowTheAnswerAndKeepButtonsOnlyWhileUnanswered() {
        SubstitutionRequest request = SubstitutionRequest.from(notice("ABSENT"), NOW);
        UUID id = Order35.CHANGE_4KI1;
        assertThat(SubstitutionTexts.noticeState(request, ChangeStatus.CONFIRMED, null)).isNull();
        assertThat(SubstitutionTexts.noticeState(request, ChangeStatus.CONFIRMED, ConfirmationDecision.CONFIRM))
                .startsWith("📌 <b>Вам поставили заміну</b>").endsWith("👍 <b>Добре, заміну прийнято.</b> Дякуємо!");
        assertThat(SubstitutionTexts.noticeState(request, ChangeStatus.DECLINED, ConfirmationDecision.DECLINE))
                .endsWith("❌ <b>Ви відмовилися від заміни.</b> Диспетчер обере інший варіант.");
        assertThat(SubstitutionTexts.noticeState(request, ChangeStatus.APPLIED, ConfirmationDecision.DECLINE))
                .endsWith("❗ <b>Ви повідомили, що не можете.</b> Заміна вже в розпорядженні, тож диспетчера "
                        + "повідомлено: він внесе доповнення.");
        assertThat(SubstitutionTexts.noticeState(request, ChangeStatus.APPLIED, null))
                .endsWith("📄 Заміну внесено до розпорядження.");
        assertThat(SubstitutionTexts.noticeState(request, ChangeStatus.CANCELLED, null)).contains("Заміну скасовано.");

        assertThat(SubstitutionTexts.noticeKeyboard(id, ChangeStatus.APPLIED, null)).isNotNull();
        assertThat(SubstitutionTexts.noticeKeyboard(id, ChangeStatus.CONFIRMED, null)).isNotNull();
        assertThat(SubstitutionTexts.noticeKeyboard(id, ChangeStatus.CONFIRMED, ConfirmationDecision.CONFIRM)).isNull();
        assertThat(SubstitutionTexts.noticeKeyboard(id, ChangeStatus.CANCELLED, null)).isNull();

        assertThat(SubstitutionTexts.noticeToast(ChangeStatus.CONFIRMED, ConfirmationDecision.CONFIRM))
                .isEqualTo("Дякуємо! 👍");
        assertThat(SubstitutionTexts.noticeToast(ChangeStatus.DECLINED, ConfirmationDecision.DECLINE))
                .isEqualTo("Відмову передано диспетчеру");
        assertThat(SubstitutionTexts.noticeToast(ChangeStatus.APPLIED, ConfirmationDecision.DECLINE))
                .isEqualTo("Диспетчера повідомлено");
    }

    @Test
    void finalStatesReplaceTheButtons() {
        SubstitutionRequest request = SubstitutionRequest.from(event(0), NOW);
        assertThat(SubstitutionTexts.finalState(request, ChangeStatus.CONFIRMED))
                .endsWith("✅ <b>Ви підтвердили заміну.</b> Дякуємо!")
                .contains("👥 4КІ1\n")
                .doesNotContain("Будь ласка");
        assertThat(SubstitutionTexts.finalState(request, ChangeStatus.DECLINED)).endsWith(
                "❌ <b>Ви відмовилися від заміни.</b> Диспетчер обере інший варіант.");
        assertThat(SubstitutionTexts.finalState(request, ChangeStatus.CANCELLED)).contains("Заміну скасовано.");
        assertThat(SubstitutionTexts.finalState(request, ChangeStatus.APPLIED)).contains("внесено до розпорядження");
        assertThat(SubstitutionTexts.finalState(request, ChangeStatus.PENDING_CONFIRMATION)).isNull();
        assertThat(SubstitutionTexts.finalState(null, ChangeStatus.CONFIRMED))
                .isEqualTo("<b>Прохання про заміну</b>\n\n✅ <b>Ви підтвердили заміну.</b> Дякуємо!");
        assertThat(SubstitutionTexts.outdated(request)).endsWith("ℹ️ <b>Цей запит уже неактуальний.</b>");
    }

    @Test
    void adminTextsNameTheTeacherAndTheLesson() {
        SubstitutionRequest request = SubstitutionRequest.from(event(0), NOW);
        assertThat(SubstitutionTexts.adminDeclined("Непийвода М.В.", request, ""))
                .isEqualTo("❌ <b>Відмова від заміни</b>\nНепийвода М.В. не може взяти 2 пару 4КІ1 у понеділок, "
                        + "19 жовтня. Оберіть інший варіант на сторінці змін.");
        assertThat(SubstitutionTexts.adminDeclinedUnknown("Непийвода М.В.", Order35.DATE, "4КІ1", ""))
                .isEqualTo("❌ <b>Відмова від заміни</b>\nНепийвода М.В. не може взяти пару 4КІ1 у понеділок, "
                        + "19 жовтня. Оберіть інший варіант на сторінці змін.");
        assertThat(SubstitutionTexts.adminObjection("Непийвода М.В.", 2, "4КІ1", Order35.DATE, ""))
                .isEqualTo("❗ <b>Потрібне доповнення до розпорядження</b>\nНепийвода М.В. не може взяти 2 пару 4КІ1 "
                        + "у понеділок, 19 жовтня, а заміна вже в опублікованому розпорядженні. Оберіть інший варіант "
                        + "на сторінці змін — він піде в доповнення.");
        assertThat(SubstitutionTexts.adminNoTelegram("Ганза", List.of(request), ""))
                .isEqualTo("⚠️ <b>Ганза не підключив Telegram</b>\nЗапит на заміну не надіслано: понеділок, 19 жовтня"
                        + " · 2 пара · 4КІ1 · Надійність, діагностика та експлуатація комп'ютерних систем і мереж.\n"
                        + "Підтвердьте заміну в панелі або зв’яжіться з викладачем.");
        assertThat(SubstitutionTexts.adminNoTelegram("Ганза", List.of(SubstitutionRequest.from(notice("ABSENT"), NOW)),
                "")).isEqualTo("⚠️ <b>Ганза не підключив Telegram — повідомте про заміну самі</b>\n"
                + "Повідомлення не надіслано: понеділок, 19 жовтня · 2 пара · 4КІ1 · " + NDKS + ".");
        assertThat(SubstitutionTexts.adminOverdue("Ганза", request, "")).contains("не відповів до 18.10 о 20:00");
    }

    private static SubstitutionConfirmationRequested event(int subgroup) {
        return new SubstitutionConfirmationRequested(UUID.randomUUID(), NOW, 1, Order35.CHANGE_4KI1,
                Order35.TEACHER_NEPYIVODA, Order35.DATE, 2, LocalTime.of(9, 40), LocalTime.of(10, 40), "4КІ1",
                subgroup, NDKS, "Кренцін М.Д.", "6", Instant.parse("2026-10-18T17:00:00Z"), null, null, null, null);
    }

    private static SubstitutionConfirmationRequested notice(String reasonCode) {
        return new SubstitutionConfirmationRequested(UUID.randomUUID(), NOW, 1, Order35.CHANGE_4KI1,
                Order35.TEACHER_NEPYIVODA, Order35.DATE, 2, LocalTime.of(9, 40), LocalTime.of(10, 40), "4КІ1", 0,
                NDKS, "Кренцін М.Д.", "6", null, SubstitutionRequestMode.NOTIFY, ChangeKind.REPLACE_DISCIPLINE,
                "Сучасні технології проєктування", reasonCode);
    }
}
