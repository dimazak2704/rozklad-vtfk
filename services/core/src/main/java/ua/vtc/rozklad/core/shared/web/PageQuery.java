package ua.vtc.rozklad.core.shared.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import ua.vtc.rozklad.core.shared.error.FieldValidationException;

/**
 * Paging and sorting of a list endpoint: {@code ?page=0&size=20&sort=name,asc} (ARCHITECTURE §9).
 * Declare it as a controller parameter; {@link PageQueryArgumentResolver} reads and validates the
 * query. Every endpoint whitelists its sortable fields in {@link #toPageable(Map, Sort)}, so an
 * unknown field is a 400 instead of a database error.
 *
 * @param page zero-based page number
 * @param size page size, {@value #MIN_SIZE}..{@value #MAX_SIZE}
 * @param sort requested orders in priority order (may be empty)
 */
public record PageQuery(int page, int size, List<SortOrder> sort) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MIN_SIZE = 1;
    public static final int MAX_SIZE = 200;

    public PageQuery {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be within " + MIN_SIZE + ".." + MAX_SIZE);
        }
        sort = sort == null ? List.of() : List.copyOf(sort);
    }

    public static PageQuery firstPage(int size) {
        return new PageQuery(0, size, List.of());
    }

    /**
     * @param sortableFields API field name → entity property path (e.g. {@code "name" → "name"})
     * @param defaultSort    order when the request has none
     * @throws FieldValidationException when a requested field is not sortable
     */
    public Pageable toPageable(Map<String, String> sortableFields, Sort defaultSort) {
        Objects.requireNonNull(sortableFields, "sortableFields");
        List<Sort.Order> orders = new ArrayList<>();
        for (SortOrder order : sort) {
            String property = sortableFields.get(order.field());
            if (property == null) {
                String allowed = sortableFields.keySet().stream().sorted().collect(Collectors.joining(", "));
                throw FieldValidationException.of("sort",
                        "Сортування за полем «%s» не підтримується. Доступні поля: %s.".formatted(order.field(), allowed));
            }
            orders.add(new Sort.Order(order.direction(), property));
        }
        Sort requested = orders.isEmpty() ? defaultSort : Sort.by(orders);
        // id в кінці робить порядок стабільним між сторінками, коли значення поля однакові
        return PageRequest.of(page, size, requested.and(Sort.by("id")));
    }

    /** One {@code sort} parameter: {@code field} or {@code field,asc|desc}. */
    public record SortOrder(String field, Sort.Direction direction) {

        public SortOrder {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(direction, "direction");
        }
    }
}
