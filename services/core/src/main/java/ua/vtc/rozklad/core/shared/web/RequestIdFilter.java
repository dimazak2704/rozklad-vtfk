package ua.vtc.rozklad.core.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Takes {@code X-Request-Id} from the gateway (or generates one), puts it into the MDC as
 * {@value #MDC_KEY} for every log line of the request and echoes it in the response.
 * Runs before Spring Security so that authentication errors are correlated too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    // захист від log injection: приймаємо лише короткі безпечні ідентифікатори
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // async-dispatch (SSE, async MVC) повторно проходить фільтр: беремо той самий id з атрибута
        String requestId = request.getAttribute(MDC_KEY) instanceof String stored
                ? stored
                : resolve(request.getHeader(HEADER));
        request.setAttribute(MDC_KEY, requestId);
        MDC.put(MDC_KEY, requestId);
        if (!response.isCommitted()) {
            response.setHeader(HEADER, requestId);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    static String resolve(String incoming) {
        if (incoming != null && SAFE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }
}
