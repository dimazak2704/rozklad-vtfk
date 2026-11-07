package ua.vtc.rozklad.notification.shared;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/** Pagination envelope of CONTRACTS.md: {@code {items, page, size, total}}. */
public record PageResponse<T>(List<T> items, int page, int size, long total) {

    public PageResponse {
        items = List.copyOf(items);
    }

    public static <E, T> PageResponse<T> of(Page<E> page, Function<? super E, ? extends T> mapper) {
        List<T> items = page.getContent().stream().<T>map(mapper).toList();
        return new PageResponse<>(items, page.getNumber(), page.getSize(), page.getTotalElements());
    }
}
