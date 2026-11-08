package ua.vtc.rozklad.core.shared.web;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.jdk.StringDeserializer;
import tools.jackson.databind.exc.InvalidFormatException;

/**
 * Rejects the NUL character (U+0000) in every JSON string: PostgreSQL cannot store it in
 * text columns, so without this check the insert fails and the client gets a misleading 409. The
 * failure becomes a 400 validation error of that field ({@code ApiExceptionHandler}).
 */
public class NulFreeStringDeserializer extends StringDeserializer {

    public static final String MESSAGE = "Текст містить недопустимий символ NUL (U+0000).";

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
        String value = super.deserialize(parser, context);
        if (value != null && value.indexOf('\0') >= 0) {
            throw InvalidFormatException.from(parser, MESSAGE, value, String.class);
        }
        return value;
    }
}
