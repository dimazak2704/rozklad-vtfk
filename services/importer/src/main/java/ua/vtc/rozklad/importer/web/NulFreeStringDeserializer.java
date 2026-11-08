package ua.vtc.rozklad.importer.web;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.jdk.StringDeserializer;
import tools.jackson.databind.exc.InvalidFormatException;

/**
 * Rejects the NUL character (U+0000) in every JSON string, as core does: PostgreSQL cannot store it
 * in text or JSON columns, so without this check the write fails and the client gets a misleading
 * error. The failure becomes a 400 validation error of that field ({@link RequestProblems}).
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
