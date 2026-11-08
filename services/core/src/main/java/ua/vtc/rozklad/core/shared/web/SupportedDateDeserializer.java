package ua.vtc.rozklad.core.shared.web;

import java.time.temporal.TemporalAccessor;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.exc.InvalidFormatException;

/**
 * JSON dates ({@code LocalDate}, {@code YearMonth}) limited to {@link SupportedDates}: reading is left
 * to Jackson's own deserializer, a value outside the range becomes an {@link InvalidFormatException}
 * of that field (400 validation, {@code errors[{field: "dateFrom"}]}).
 */
public class SupportedDateDeserializer<T extends TemporalAccessor> extends StdDeserializer<T> {

    private final ValueDeserializer<T> delegate;

    public SupportedDateDeserializer(Class<T> type, ValueDeserializer<T> delegate) {
        super(type);
        this.delegate = delegate;
    }

    @Override
    public T deserialize(JsonParser parser, DeserializationContext context) {
        T value = delegate.deserialize(parser, context);
        if (value != null && !SupportedDates.supported(value)) {
            throw InvalidFormatException.from(parser, SupportedDates.MESSAGE, value, handledType());
        }
        return value;
    }
}
