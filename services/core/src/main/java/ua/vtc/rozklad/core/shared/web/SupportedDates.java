package ua.vtc.rozklad.core.shared.web;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAccessor;

/**
 * Dates the API accepts: {@value #MIN_YEAR}–{@value #MAX_YEAR}. A college timetable has no business
 * outside this range, and extreme years ({@code +999999999-12-31}) overflow date arithmetic and
 * PostgreSQL {@code date}, which used to surface as 500 or a false «record already exists» 409. Every
 * {@link LocalDate} and {@link YearMonth} of a query parameter ({@link SupportedDateFormatterFactory})
 * or a JSON body ({@link JacksonConfig}) is checked while binding, so the client gets a 400 on that field.
 */
public final class SupportedDates {

    public static final int MIN_YEAR = 2000;
    public static final int MAX_YEAR = 2100;
    public static final String MESSAGE = "Дата має бути в межах %d–%d років.".formatted(MIN_YEAR, MAX_YEAR);

    private SupportedDates() {
    }

    public static boolean supported(TemporalAccessor value) {
        int year = switch (value) {
            case LocalDate date -> date.getYear();
            case YearMonth month -> month.getYear();
            default -> throw new IllegalArgumentException("Not a date: " + value.getClass());
        };
        return year >= MIN_YEAR && year <= MAX_YEAR;
    }

    /** @throws UnsupportedDateException when the date is outside the supported years */
    public static <T extends TemporalAccessor> T require(T value) {
        if (value != null && !supported(value)) {
            throw new UnsupportedDateException();
        }
        return value;
    }

    /** A date that parsed but lies outside the supported years. */
    public static final class UnsupportedDateException extends IllegalArgumentException {

        public UnsupportedDateException() {
            super(MESSAGE);
        }
    }
}
