package ua.vtc.rozklad.notification.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.AbsenceReasonCategory;
import ua.vtc.rozklad.contracts.AbsenceSource;
import ua.vtc.rozklad.notification.core.CoreClient.AbsenceReport;
import ua.vtc.rozklad.notification.core.ImporterClient.AbsenceParse;
import ua.vtc.rozklad.notification.core.UpstreamRejectedException;
import ua.vtc.rozklad.notification.core.UpstreamUnavailableException;
import ua.vtc.rozklad.notification.support.CoreFixtures;
import ua.vtc.rozklad.notification.support.FakeTelegramClient.Edit;
import ua.vtc.rozklad.notification.support.FakeTelegramClient.Sent;
import ua.vtc.rozklad.notification.support.IntegrationTest;

/** Absence wizard: buttons and inline calendar, AI free text, core answers. */
class AbsenceWizardIT extends IntegrationTest {

    private static final long TEACHER = 2001;

    private long wizardMessage;

    @BeforeEach
    void bindTeacher() {
        teacherChat(TEACHER, CoreFixtures.TEACHER_NEPYIVODA, "Непийвода М.В.");
    }

    @Test
    void oneDayPickedPairsSickLeave() {
        say(TEACHER, "🤒 Відсутність");
        Sent start = telegram.lastSentTo(TEACHER);
        wizardMessage = start.messageId();
        assertThat(start.text()).contains("Коли вас не буде?").contains("«4.11 не буду, лікарняний»");
        assertThat(start.buttons()).containsExactly("Сьогодні, Пн 19.10", "Завтра, Вт 20.10", "📅 Інша дата",
                "✖️ Скасувати");

        click(start.callbackOf("Сьогодні, Пн 19.10").orElseThrow());
        assertThat(lastEdit().text()).contains("📅 Початок: <b>понеділок, 19 жовтня</b>\nЦе один день чи кілька?");
        click("ab:one");
        assertThat(lastEdit().buttons()).containsExactly("Увесь день", "Обрати пари", "⬅️ Назад", "✖️ Скасувати");
        click("ab:pick");
        click("ab:p:1");
        click("ab:p:2");
        click("ab:p:3");
        click("ab:p:3");
        assertThat(lastEdit().buttons()).startsWith("✅ 1", "✅ 2", "3", "4", "5", "6", "7", "8", "Готово");
        click("ab:p:ok");
        assertThat(lastEdit().text()).contains("🕘 пари 1, 2").contains("Причина?");
        click("ab:r:SICK_LEAVE");
        Edit confirm = lastEdit();
        assertThat(confirm.text()).isEqualTo("<b>Перевірте, будь ласка:</b>\n📅 понеділок, 19 жовтня\n🕘 пари 1, 2\n"
                + "📝 хвороба / лікарняний\n\nНадіслати диспетчеру?");
        assertThat(confirm.buttons()).containsExactly("✅ Надіслати", "✏️ Змінити", "✖️ Скасувати");

        click("ab:send");
        assertThat(core.absences()).containsExactly(new AbsenceReport(CoreFixtures.TEACHER_NEPYIVODA,
                LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 19), List.of(1, 2),
                AbsenceReasonCategory.SICK_LEAVE, null, AbsenceSource.BOT));
        assertThat(lastEdit().text()).startsWith("✅ <b>Готово!</b> Диспетчер отримав повідомлення:")
                .endsWith("Одужуйте! 💙");
        assertThat(lastEdit().keyboard()).isNull();
        assertThat(telegram.lastCallbackAnswer().text()).isEqualTo("Надіслано ✅");

        // повторне натискання старої кнопки вже нічого не відправить
        click("ab:send");
        assertThat(core.absences()).hasSize(1);
        assertThat(telegram.lastCallbackAnswer()).satisfies(answer -> {
            assertThat(answer.alert()).isTrue();
            assertThat(answer.text()).isEqualTo(BotTexts.STALE_BUTTON);
        });
    }

    @Test
    void calendarRangeWholeDayVacationWithBackNavigation() {
        say(TEACHER, "/absence");
        wizardMessage = telegram.lastSentTo(TEACHER).messageId();
        click("ab:cal:202610");
        assertThat(lastEdit().buttons()).contains("Жовтень 2026", "19", "30").doesNotContain("16");
        click("ab:cal:202611");
        assertThat(lastEdit().buttons()).contains("Листопад 2026", "2", "30");
        click("ab:cal:202610");
        click("ab:d:20261021");
        click("ab:range");
        assertThat(lastEdit().text()).contains("Оберіть останній день відсутності:");
        assertThat(lastEdit().buttons()).doesNotContain("20").contains("21", "23");

        click("ab:d:20261020");
        assertThat(telegram.lastCallbackAnswer().text()).isEqualTo("Останній день не може бути раніше за перший.");
        click("ab:d:20261023");
        click("ab:all");
        click("ab:r:PERSONAL");
        click("ab:back");
        assertThat(lastEdit().text()).contains("Причина?");
        click("ab:r:VACATION");
        assertThat(lastEdit().text()).contains("📅 21–23 жовтня\n🕘 увесь день\n📝 відпустка");
        click("ab:send");

        assertThat(core.absences()).singleElement().satisfies(report -> {
            assertThat(report.dateFrom()).isEqualTo(LocalDate.of(2026, 10, 21));
            assertThat(report.dateTo()).isEqualTo(LocalDate.of(2026, 10, 23));
            assertThat(report.pairs()).isNull();
            assertThat(report.reasonCategory()).isEqualTo(AbsenceReasonCategory.VACATION);
        });
        assertThat(lastEdit().text()).endsWith("Гарного відпочинку! 🌿");
    }

    @Test
    void freeTextIsParsedByAiEchoedAndSentWithTheTeachersWords() {
        importer.answer(new AbsenceParse(LocalDate.of(2026, 11, 4), null, null, AbsenceReasonCategory.SICK_LEAVE,
                0.93, null));
        say(TEACHER, "4.11 не буду, лікарняний");

        assertThat(importer.requests()).singleElement().satisfies(request -> {
            assertThat(request.teacherId()).isEqualTo(CoreFixtures.TEACHER_NEPYIVODA);
            assertThat(request.today()).isEqualTo(LocalDate.of(2026, 10, 19));
        });
        Sent echo = telegram.lastSentTo(TEACHER);
        assertThat(echo.text()).isEqualTo("<b>Я зрозумів так:</b>\n📅 середа, 4 листопада\n🕘 увесь день\n"
                + "📝 хвороба / лікарняний\n\nВсе правильно?");
        assertThat(echo.buttons()).containsExactly("✅ Так, надіслати", "✏️ Змінити", "✖️ Скасувати");

        wizardMessage = echo.messageId();
        click("ab:send");
        assertThat(core.absences()).singleElement().satisfies(report -> {
            assertThat(report.dateFrom()).isEqualTo(LocalDate.of(2026, 11, 4));
            assertThat(report.comment()).isEqualTo("4.11 не буду, лікарняний");
            assertThat(report.source()).isEqualTo(AbsenceSource.BOT);
        });
    }

    @Test
    void aiWithoutReasonAsksForItAndEditRestartsTheWizard() {
        importer.answer(new AbsenceParse(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20), List.of(3), null, 0.8,
                null));
        say(TEACHER, "завтра не зможу на 3 пару");
        Sent reason = telegram.lastSentTo(TEACHER);
        assertThat(reason.text()).contains("📅 <b>вівторок, 20 жовтня</b> · 🕘 3 пара").contains("Причина?");
        wizardMessage = reason.messageId();
        click("ab:r:BUSINESS_TRIP");
        click("ab:edit");
        assertThat(lastEdit().text()).contains("Коли вас не буде?");
    }

    @Test
    void unclearOrUnavailableAiFallsBackToButtons() {
        importer.answer(new AbsenceParse(null, null, null, null, 0.3, "Уточніть, будь ласка, дату."));
        say(TEACHER, "мене не буде, хворію");
        assertThat(telegram.lastSentTo(TEACHER).text()).startsWith("🤔 Уточніть, будь ласка, дату.");
        assertThat(telegram.lastSentTo(TEACHER).buttons()).contains("📅 Інша дата");

        importer.fail(new UpstreamUnavailableException("importer", "down", null));
        say(TEACHER, "не буду 5.11");
        assertThat(telegram.lastSentTo(TEACHER).text()).startsWith("😕 Не вдалося розпізнати текст");

        importer.answer(new AbsenceParse(LocalDate.of(2026, 10, 1), null, null, AbsenceReasonCategory.OTHER, 0.9,
                null));
        importer.fail(null);
        say(TEACHER, "1.10 не буду");
        assertThat(telegram.lastSentTo(TEACHER).text()).startsWith("🤔 Дата 01.10 уже минула.");
        assertThat(core.absences()).isEmpty();
    }

    @Test
    void coreRejectionIsShownAndAnOutageKeepsTheButtons() {
        importer.answer(new AbsenceParse(LocalDate.of(2026, 10, 20), null, null, AbsenceReasonCategory.OTHER, 0.9,
                null));
        say(TEACHER, "завтра не буду");
        wizardMessage = telegram.lastSentTo(TEACHER).messageId();

        core.failAbsences(new UpstreamUnavailableException("core", "down", null));
        click("ab:send");
        assertThat(telegram.lastCallbackAnswer().alert()).isTrue();
        assertThat(telegram.editsIn(TEACHER)).isEmpty();

        core.failAbsences(new UpstreamRejectedException(422, "Ви вже повідомили про відсутність на цю дату."));
        click("ab:send");
        assertThat(lastEdit().text())
                .isEqualTo("⚠️ <b>Не вдалося надіслати</b>: Ви вже повідомили про відсутність на цю дату.\n"
                        + "Змініть дані й спробуйте ще раз.");
        assertThat(lastEdit().buttons()).containsExactly("✏️ Змінити", "✖️ Скасувати");
    }

    @Test
    void cancelAndNonTeachers() {
        say(TEACHER, "🤒 Відсутність");
        wizardMessage = telegram.lastSentTo(TEACHER).messageId();
        // кнопка зі старого повідомлення, коли перший день ще не обрано
        click("ab:r:OTHER");
        assertThat(telegram.lastCallbackAnswer().text()).isEqualTo(BotTexts.STALE_BUTTON);
        assertThat(telegram.editsIn(TEACHER)).isEmpty();
        click("ab:x");
        assertThat(lastEdit().text()).startsWith("Добре, скасовано.");
        click("ab:one");
        assertThat(telegram.lastCallbackAnswer().text()).isEqualTo(BotTexts.STALE_BUTTON);

        owner();
        say(OWNER_CHAT, "🤒 Відсутність");
        assertThat(telegram.lastSentTo(OWNER_CHAT).text()).isEqualTo(BotTexts.TEACHERS_ONLY);
        // текст «не буду» від не-викладача — це просто пошук
        say(OWNER_CHAT, "не буду");
        assertThat(importer.requests()).isEmpty();
    }

    private void click(String data) {
        press(TEACHER, wizardMessage, data);
    }

    private Edit lastEdit() {
        return telegram.lastEditIn(TEACHER);
    }
}
