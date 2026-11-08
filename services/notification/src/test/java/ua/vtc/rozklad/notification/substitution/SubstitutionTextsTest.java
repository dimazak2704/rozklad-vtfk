package ua.vtc.rozklad.notification.substitution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.events.SubstitutionConfirmationRequested;
import ua.vtc.rozklad.notification.support.Order35;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineButton;

class SubstitutionTextsTest {

    private static final Instant NOW = Instant.parse("2026-10-18T15:30:00Z");

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
                        org.assertj.core.groups.Tuple.tuple("✅ Підтверджую", "sr:c:" + Order35.CHANGE_4KI1),
                        org.assertj.core.groups.Tuple.tuple("❌ Не можу", "sr:d:" + Order35.CHANGE_4KI1));
    }

    @Test
    void finalStatesReplaceTheButtons() {
        SubstitutionRequest request = SubstitutionRequest.from(event(0), NOW);
        assertThat(SubstitutionTexts.finalState(request, ChangeStatus.CONFIRMED))
                .endsWith("✅ <b>Ви підтвердили заміну.</b> Дякуємо!")
                .contains("👥 4КІ1\n")
                .doesNotContain("Будь ласка");
        assertThat(SubstitutionTexts.finalState(request, ChangeStatus.DECLINED)).endsWith(
                "❌ <b>Ви відмовилися від заміни.</b> Диспетчер підбере іншого викладача.");
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
                .isEqualTo("❌ <b>Відмова від заміни</b>\nНепийвода М.В. не може провести пару: понеділок, 19 жовтня"
                        + " · 2 пара · 4КІ1 · Надійність, діагностика та експлуатація комп'ютерних систем і мереж\n"
                        + "Підберіть іншого викладача.");
        assertThat(SubstitutionTexts.adminNoTelegram("Ганза", java.util.List.of(request), ""))
                .isEqualTo("⚠️ <b>Ганза не підключив Telegram</b>\nЗапит на заміну не надіслано: понеділок, 19 жовтня"
                        + " · 2 пара · 4КІ1 · Надійність, діагностика та експлуатація комп'ютерних систем і мереж.\n"
                        + "Підтвердьте заміну в панелі або зв’яжіться з викладачем.");
        assertThat(SubstitutionTexts.adminOverdue("Ганза", request, "")).contains("не відповів до 18.10 о 20:00");
    }

    private static SubstitutionConfirmationRequested event(int subgroup) {
        return new SubstitutionConfirmationRequested(UUID.randomUUID(), NOW, 1, Order35.CHANGE_4KI1,
                Order35.TEACHER_NEPYIVODA, Order35.DATE, 2, LocalTime.of(9, 40), LocalTime.of(10, 40), "4КІ1",
                subgroup, "Надійність, діагностика та експлуатація комп'ютерних систем і мереж", "Кренцін М.Д.", "6",
                Instant.parse("2026-10-18T17:00:00Z"));
    }
}
