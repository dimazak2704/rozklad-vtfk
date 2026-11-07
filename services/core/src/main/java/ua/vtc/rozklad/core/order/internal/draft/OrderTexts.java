package ua.vtc.rozklad.core.order.internal.draft;

import java.time.LocalDate;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.shared.text.UkrainianText;

/**
 * Exact wording of the college order (DOMAIN §8, orders №35/№96/№105):
 * <pre>
 * РОЗПОРЯДЖЕННЯ №35
 * По коледжу про деякі зміни в розкладі занять
 * На 19 жовтня 2026 р.
 * ПОНЕДІЛОК - ЗНАМЕННИК
 * </pre>
 */
public final class OrderTexts {

    public static final String SUBTITLE = "По коледжу про деякі зміни в розкладі занять";
    public static final String NO_PAIR = "Пари не буде";
    public static final String ADDITIONAL = "ДОДАТКОВО";
    public static final String AS_SCHEDULED = "За розкладом";
    /** Marks a lesson of one subgroup («Крисак Л. (п.)»). */
    public static final String ONE_SUBGROUP = " (п.)";

    private OrderTexts() {
    }

    /** «РОЗПОРЯДЖЕННЯ №35»; a draft has no number yet. */
    public static String mainTitle(Integer number) {
        return number == null ? "РОЗПОРЯДЖЕННЯ" : "РОЗПОРЯДЖЕННЯ №" + number;
    }

    /** «ДОПОВНЕННЯ до розпорядження №35». */
    public static String amendmentTitle(int parentNumber) {
        return "ДОПОВНЕННЯ до розпорядження №" + parentNumber;
    }

    /** «На 19 жовтня 2026 р.». */
    public static String dateLine(LocalDate date) {
        return "На " + UkrainianText.dayMonthYear(date) + " р.";
    }

    /** «ПОНЕДІЛОК». */
    public static String weekdayLabel(LocalDate date) {
        return UkrainianText.weekdayUpper(date.getDayOfWeek());
    }

    /** «ЗНАМЕННИК». */
    public static String parityLabel(Parity parity) {
        return UkrainianText.parityUpper(parity);
    }

    /** «ПОНЕДІЛОК - ЗНАМЕННИК». */
    public static String dayLine(String weekdayLabel, String parityLabel) {
        return weekdayLabel + " - " + parityLabel;
    }

    /** «2» or «1-4». */
    public static String pairLabel(int pairFrom, int pairTo) {
        return pairFrom == pairTo ? String.valueOf(pairFrom) : pairFrom + "-" + pairTo;
    }
}
