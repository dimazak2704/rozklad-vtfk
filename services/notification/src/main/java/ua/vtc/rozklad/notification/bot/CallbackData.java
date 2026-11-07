package ua.vtc.rozklad.notification.bot;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import ua.vtc.rozklad.contracts.AbsenceReasonCategory;
import ua.vtc.rozklad.notification.core.OwnerType;

/**
 * Callback data of inline buttons (≤ 64 bytes, ASCII): {@code <area>:<action>[:<args>]}.
 * Substitution buttons ({@code sr:c|d:<changeId>}) are defined next to their texts.
 */
public final class CallbackData {

    public static final String NOOP = "noop";

    static final String COURSES = "mg:courses";
    static final String DIGEST_ON = "dg:on";
    static final String DIGEST_OFF = "dg:off";

    static final String ABSENCE_ONE_DAY = "ab:one";
    static final String ABSENCE_RANGE = "ab:range";
    static final String ABSENCE_ALL_DAY = "ab:all";
    static final String ABSENCE_PICK_PAIRS = "ab:pick";
    static final String ABSENCE_PAIRS_DONE = "ab:p:ok";
    static final String ABSENCE_SEND = "ab:send";
    static final String ABSENCE_EDIT = "ab:edit";
    static final String ABSENCE_BACK = "ab:back";
    static final String ABSENCE_CANCEL = "ab:x";

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private CallbackData() {
    }

    static String course(int course) {
        return "mg:c:" + course;
    }

    static String group(UUID groupId) {
        return "mg:g:" + groupId;
    }

    static String subscribe(UUID groupId) {
        return "sub:" + groupId;
    }

    static String day(OwnerType type, UUID ownerId, LocalDate date) {
        return "d:" + type.code() + ":" + ownerId + ":" + date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    static String pick(OwnerType type, UUID ownerId) {
        return "s:" + type.code() + ":" + ownerId;
    }

    static String absenceDate(LocalDate date) {
        return "ab:d:" + date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    static String absenceMonth(YearMonth month) {
        return "ab:cal:" + month.format(MONTH);
    }

    static String absencePair(int pair) {
        return "ab:p:" + pair;
    }

    static String absenceReason(AbsenceReasonCategory reason) {
        return "ab:r:" + reason.name();
    }

    static LocalDate parseDate(String value) {
        return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
    }

    static YearMonth parseMonth(String value) {
        return YearMonth.parse(value, MONTH);
    }
}
