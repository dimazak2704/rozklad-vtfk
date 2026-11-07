package ua.vtc.rozklad.notification.bot;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import ua.vtc.rozklad.contracts.AbsenceReasonCategory;

/**
 * Unfinished conversation of one private chat, kept in Redis for {@code app.bot.dialog-ttl}:
 * waiting for a search query, or the absence wizard with what is chosen so far.
 */
public record Dialog(Mode mode, AbsenceDraft absence) {

    public enum Mode {
        SEARCH,
        ABSENCE
    }

    public Dialog {
        Objects.requireNonNull(mode, "mode");
        if (mode == Mode.ABSENCE) {
            Objects.requireNonNull(absence, "absence");
        }
    }

    public static Dialog search() {
        return new Dialog(Mode.SEARCH, null);
    }

    public static Dialog absence(AbsenceDraft draft) {
        return new Dialog(Mode.ABSENCE, draft);
    }

    /**
     * Absence wizard state. {@code pairs == null} after the PAIRS step means the whole day;
     * {@code comment} keeps the teacher's own text when the draft came from AI parsing.
     */
    public record AbsenceDraft(Step step, LocalDate dateFrom, LocalDate dateTo, List<Integer> pairs,
                               AbsenceReasonCategory reason, YearMonth month, String comment) {

        public enum Step {
            DATE_FROM,
            RANGE,
            DATE_TO,
            PAIRS,
            PAIR_PICK,
            REASON,
            CONFIRM
        }

        public AbsenceDraft {
            Objects.requireNonNull(step, "step");
            pairs = pairs == null ? null : List.copyOf(pairs);
        }

        public static AbsenceDraft start(YearMonth month) {
            return new AbsenceDraft(Step.DATE_FROM, null, null, null, null, month, null);
        }

        public AbsenceDraft at(Step value) {
            return new AbsenceDraft(value, dateFrom, dateTo, pairs, reason, month, comment);
        }

        public AbsenceDraft from(LocalDate value) {
            return new AbsenceDraft(step, value, null, null, reason, YearMonth.from(value), comment);
        }

        public AbsenceDraft to(LocalDate value) {
            return new AbsenceDraft(step, dateFrom, value, pairs, reason, month, comment);
        }

        public AbsenceDraft withPairs(List<Integer> value) {
            return new AbsenceDraft(step, dateFrom, dateTo, value, reason, month, comment);
        }

        public AbsenceDraft withReason(AbsenceReasonCategory value) {
            return new AbsenceDraft(step, dateFrom, dateTo, pairs, value, month, comment);
        }

        public AbsenceDraft showing(YearMonth value) {
            return new AbsenceDraft(step, dateFrom, dateTo, pairs, reason, value, comment);
        }

        public AbsenceDraft withComment(String value) {
            return new AbsenceDraft(step, dateFrom, dateTo, pairs, reason, month, value);
        }

        public LocalDate lastDay() {
            return dateTo == null ? dateFrom : dateTo;
        }
    }
}
