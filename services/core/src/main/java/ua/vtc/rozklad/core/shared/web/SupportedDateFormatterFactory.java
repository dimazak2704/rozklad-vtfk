package ua.vtc.rozklad.core.shared.web;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAccessor;
import java.util.Set;
import org.springframework.format.AnnotationFormatterFactory;
import org.springframework.format.Parser;
import org.springframework.format.Printer;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.datetime.standard.Jsr310DateTimeFormatAnnotationFormatterFactory;

/**
 * {@code @DateTimeFormat LocalDate/YearMonth} request parameters: parsed by Spring's JSR-310 factory,
 * then limited to {@link SupportedDates}. A value outside the range fails conversion like a malformed
 * one, so the API answers 400 with the parameter as the field (ApiExceptionHandler#handleTypeMismatch).
 * Registered after Spring's own factory, therefore it is consulted first.
 */
public class SupportedDateFormatterFactory implements AnnotationFormatterFactory<DateTimeFormat> {

    private final Jsr310DateTimeFormatAnnotationFormatterFactory delegate =
            new Jsr310DateTimeFormatAnnotationFormatterFactory();

    @Override
    public Set<Class<?>> getFieldTypes() {
        return Set.of(LocalDate.class, YearMonth.class);
    }

    @Override
    public Printer<?> getPrinter(DateTimeFormat annotation, Class<?> fieldType) {
        return delegate.getPrinter(annotation, fieldType);
    }

    @Override
    public Parser<?> getParser(DateTimeFormat annotation, Class<?> fieldType) {
        Parser<?> parser = delegate.getParser(annotation, fieldType);
        return (text, locale) -> SupportedDates.require((TemporalAccessor) parser.parse(text, locale));
    }
}
