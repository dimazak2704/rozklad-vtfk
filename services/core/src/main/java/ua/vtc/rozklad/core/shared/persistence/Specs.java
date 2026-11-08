package ua.vtc.rozklad.core.shared.persistence;

import jakarta.persistence.criteria.Predicate;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/** Reusable Spring Data specifications for list filters. */
public final class Specs {

    private static final char ESCAPE = '\\';

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

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
