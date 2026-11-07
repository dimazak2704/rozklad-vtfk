package ua.vtc.rozklad.importer.web;

import java.util.List;
import java.util.Map;

/** 400 with {@code errors[{field,message}]} for checks Bean Validation cannot express (JSON options, files). */
public class InvalidRequestException extends RuntimeException {

    private final List<Map.Entry<String, String>> errors;

    public InvalidRequestException(List<Map.Entry<String, String>> errors) {
        super(errors.isEmpty() ? "Некоректний запит." : errors.getFirst().getValue());
        this.errors = List.copyOf(errors);
    }

    public static InvalidRequestException field(String field, String message) {
        return new InvalidRequestException(List.of(Map.entry(field, message)));
    }

    public List<Map.Entry<String, String>> errors() {
        return errors;
    }
}
