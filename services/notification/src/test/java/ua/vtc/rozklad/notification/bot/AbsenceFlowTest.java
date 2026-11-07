package ua.vtc.rozklad.notification.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.AbsenceReasonCategory;
import ua.vtc.rozklad.notification.core.ImporterClient.AbsenceParse;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineButton;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineKeyboard;

/** Validation of AI results and the inline calendar of study days. */
class AbsenceFlowTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

    @Test
    void usableAiResultHasNoProblem() {
        assertThat(AbsenceFlow.problem(parse(LocalDate.of(2026, 11, 4), null, null, 0.93, null), TODAY)).isEmpty();
        assertThat(AbsenceFlow.problem(parse(TODAY, TODAY, List.of(1, 2), null, null), TODAY)).isEmpty();
    }

    @Test
    void unclearOrInvalidAiResultsAreExplained() {
        assertThat(AbsenceFlow.problem(null, TODAY)).contains("Не вдалося розібрати повідомлення.");
        assertThat(AbsenceFlow.problem(parse(null, null, null, 0.9, "Яка саме дата?"), TODAY))
                .contains("Яка саме дата?");
        assertThat(AbsenceFlow.problem(parse(TODAY, null, null, 0.4, null), TODAY))
                .contains("Не впевнений, що зрозумів правильно.");
        assertThat(AbsenceFlow.problem(parse(null, null, null, 0.9, null), TODAY)).contains("Не знайшов у тексті дату.");
        assertThat(AbsenceFlow.problem(parse(TODAY.plusDays(3), TODAY.plusDays(1), null, 0.9, null), TODAY))
                .contains("Кінцева дата раніша за початкову.");
        assertThat(AbsenceFlow.problem(parse(TODAY.minusDays(1), null, null, 0.9, null), TODAY))
                .contains("Дата 18.10 уже минула.");
        assertThat(AbsenceFlow.problem(parse(TODAY.plusDays(120), null, null, 0.9, null), TODAY))
                .contains("Дата надто далеко наперед.");
        assertThat(AbsenceFlow.problem(parse(TODAY, null, List.of(0, 9), 0.9, null), TODAY))
                .contains("Номери пар мають бути від 1 до 8.");
    }

    @Test
    void calendarShowsStudyDaysOfTheMonthAndDisablesPastDays() {
        InlineKeyboard calendar = AbsenceFlow.calendar(YearMonth.of(2026, 10), TODAY, TODAY.plusDays(90));
        List<List<InlineButton>> rows = calendar.rows();
        assertThat(rows.getFirst()).extracting(InlineButton::text).containsExactly("·", "Жовтень 2026", "▶️");
        assertThat(rows.get(1)).extracting(InlineButton::text).containsExactly("Пн", "Вт", "Ср", "Чт", "Пт");
        // 1.10.2026 — четвер; тиждень 28.09–2.10
        assertThat(rows.get(2)).extracting(InlineButton::text).containsExactly("·", "·", "·", "·", "·");
        assertThat(rows.get(5)).extracting(InlineButton::text).containsExactly("19", "20", "21", "22", "23");
        assertThat(rows.get(5).getFirst().callbackData()).isEqualTo("ab:d:20261019");
        assertThat(rows.getLast()).extracting(InlineButton::text).containsExactly("26", "27", "28", "29", "30");
        assertThat(rows.stream().flatMap(List::stream).filter(button -> "18".equals(button.text()))).isEmpty();

        InlineKeyboard november = AbsenceFlow.calendar(YearMonth.of(2026, 11), TODAY, TODAY.plusDays(90));
        assertThat(november.rows().getFirst().getFirst().callbackData()).isEqualTo("ab:cal:202610");
        // 1.11.2026 — неділя: перший рядок — тиждень із 2 листопада
        assertThat(november.rows().get(2)).extracting(InlineButton::text).containsExactly("2", "3", "4", "5", "6");
    }

    @Test
    void wizardTexts() {
        assertThat(AbsenceFlow.pairsText(null)).isEqualTo("увесь день");
        assertThat(AbsenceFlow.pairsText(List.of(3))).isEqualTo("3 пара");
        assertThat(AbsenceFlow.pairsText(List.of(1, 2))).isEqualTo("пари 1, 2");
        assertThat(AbsenceFlow.reasonText(AbsenceReasonCategory.SICK_LEAVE)).isEqualTo("хвороба / лікарняний");
    }

    private static AbsenceParse parse(LocalDate from, LocalDate to, List<Integer> pairs, Double confidence,
                                      String clarification) {
        return new AbsenceParse(from, to, pairs, AbsenceReasonCategory.SICK_LEAVE, confidence, clarification);
    }
}
