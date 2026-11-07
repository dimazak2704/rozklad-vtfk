package ua.vtc.rozklad.importer.web;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/** ARCHITECTURE §9 page shape {@code {items, page, size, total}}. */
public record PageResponse<T>(List<T> items, int page, int size, long total) {

    public static <S, T> PageResponse<T> of(Page<S> page, Function<S, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements());
    }
}
