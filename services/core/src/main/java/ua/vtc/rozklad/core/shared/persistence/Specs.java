package ua.vtc.rozklad.core.shared.persistence;

import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/** Reusable Spring Data specifications for list filters. */
public final class Specs {

    private static final char ESCAPE = '\\';

    /** ICU collation with the Ukrainian alphabet (А Б В Г Ґ Д Е Є Ж З И І Ї Й …), created by V1_811. */
    public static final String UKRAINIAN = "uk_ua";

    private Specs() {
    }

    /**
     * Case-insensitive "contains" over several string attributes ({@code ?q=} of list endpoints);
     * a blank {@code q} means no restriction. {@code %} and {@code _} in the query are literal.
     */
    public static <T> Specification<T> containsIgnoreCase(String q, String... attributes) {
        if (q == null || q.isBlank()) {
            return Specification.unrestricted();
        }
        String pattern = "%" + escape(q.strip().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(Arrays.stream(attributes)
                .map(attribute -> cb.like(cb.lower(root.<String>get(attribute)), pattern, ESCAPE))
                .toArray(Predicate[]::new));
    }

    /** {@code attribute = value}; {@code null} value means no restriction. */
    public static <T> Specification<T> equalTo(String attribute, Object value) {
        if (value == null) {
            return Specification.unrestricted();
        }
        return (root, query, cb) -> cb.equal(root.get(attribute), value);
    }

    /**
     * A page of {@code filter} in the order of {@code pageable}, where the string properties in {@code collated}
     * follow the Ukrainian alphabet instead of the database's byte order (which puts Є and І before А).
     * Spring Data keeps a specification's order only when the pageable has none, and drops it from the count
     * query, so the order travels in the specification and the pageable goes without it.
     */
    public static <T> PagedSpec<T> orderedUkrainian(Specification<T> filter, Pageable pageable,
                                                    Set<String> collated) {
        Sort sort = pageable.getSort();
        Specification<T> ordered = filter.and((root, query, cb) -> {
            if (!isCount(query)) {
                HibernateCriteriaBuilder hcb = (HibernateCriteriaBuilder) cb;
                List<Order> orders = new ArrayList<>();
                for (Sort.Order order : sort) {
                    Expression<?> key = collated.contains(order.getProperty())
                            ? hcb.collate(root.get(order.getProperty()), UKRAINIAN)
                            : root.get(order.getProperty());
                    orders.add(order.isAscending() ? cb.asc(key) : cb.desc(key));
                }
                query.orderBy(orders);
            }
            return null;
        });
        return new PagedSpec<>(ordered, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
    }

    /** A specification that carries the order, and the pageable without one: pass both to {@code findAll}. */
    public record PagedSpec<T>(Specification<T> spec, Pageable pageable) {
    }

    private static boolean isCount(CriteriaQuery<?> query) {
        return Long.class.equals(query.getResultType()) || long.class.equals(query.getResultType());
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
