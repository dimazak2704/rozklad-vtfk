package ua.vtc.rozklad.core.shared.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import ua.vtc.rozklad.core.shared.error.FieldErrors;

/**
 * Builds {@link PageQuery} from {@code page}, {@code size} and repeated {@code sort} query
 * parameters. Unlike Spring Data's resolver it never clamps silently: a negative page, a size over
 * {@value PageQuery#MAX_SIZE} or a malformed sort is a 400 with a Ukrainian message.
 */
public class PageQueryArgumentResolver implements HandlerMethodArgumentResolver {

    private static final Pattern SORT_FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,40}");

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return PageQuery.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest request, WebDataBinderFactory binderFactory) {
        FieldErrors errors = new FieldErrors();
        int page = parseInt(request.getParameter("page"), 0);
        if (page < 0) {
            errors.add("page", "Номер сторінки має бути цілим числом, не меншим за 0.");
        }
        int size = parseInt(request.getParameter("size"), PageQuery.DEFAULT_SIZE);
        if (size < PageQuery.MIN_SIZE || size > PageQuery.MAX_SIZE) {
            errors.add("size", "Розмір сторінки має бути цілим числом від %d до %d."
                    .formatted(PageQuery.MIN_SIZE, PageQuery.MAX_SIZE));
        }
        List<PageQuery.SortOrder> orders = new ArrayList<>();
        String[] sorts = request.getParameterValues("sort");
        if (sorts != null) {
            for (String sort : sorts) {
                PageQuery.SortOrder order = parseSort(sort);
                if (order == null) {
                    errors.add("sort", "Сортування має вигляд «поле», «поле,asc» або «поле,desc».");
                } else {
                    orders.add(order);
                }
            }
        }
        errors.throwIfAny();
        return new PageQuery(page, size, orders);
    }

    /** {@code null} → default; anything that is not an integer → -1 (reported as invalid). */
    private static int parseInt(String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException notNumber) {
            return -1;
        }
    }

    private static PageQuery.SortOrder parseSort(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(",", -1);
        if (parts.length > 2 || !SORT_FIELD.matcher(parts[0].trim()).matches()) {
            return null;
        }
        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length == 2) {
            String value = parts[1].trim().toLowerCase(Locale.ROOT);
            if (value.equals("desc")) {
                direction = Sort.Direction.DESC;
            } else if (!value.equals("asc")) {
                return null;
            }
        }
        return new PageQuery.SortOrder(parts[0].trim(), direction);
    }
}
