package ua.vtc.rozklad.gateway;

import java.util.Arrays;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * Service-to-service API is not served from the edge. Notification and the importer call core and the
 * importer directly over the container network ({@code CORE_URL}, {@code IMPORTER_URL}); nothing in the
 * browser uses these paths. Without this filter a leaked service client secret would be enough to mint a
 * service token at {@code /auth/oauth2/token} and, through Caddy and the gateway, answer substitutions as
 * a teacher, report absences or write catalog and timetable data. The paths are matched decoded and
 * case-insensitively ({@code /api/%69nternal} is {@code /api/internal} for core) and answered 404, the way
 * Caddy and nginx answer {@code /auth/internal}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 4)
public class PrivatePathFilter implements WebFilter {

    private static final List<PathPattern> PRIVATE = parse(
            "/api/internal/**",                // core: bot replies, absences, teachers, catalog snapshot
            "/api/admin/import/**",            // core: the importer applies its jobs
            "/api/import/ai/parse-absence/**"  // importer: free-text absences from the bot
    );

    private final GatewayErrorHandler errors;

    public PrivatePathFilter(GatewayErrorHandler errors) {
        this.errors = errors;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (isPrivate(exchange.getRequest().getPath().pathWithinApplication())) {
            return errors.write(exchange, exchange.getResponse(),
                    GatewayErrorHandler.fromStatus(HttpStatus.NOT_FOUND));
        }
        return chain.filter(exchange);
    }

    static boolean isPrivate(PathContainer path) {
        for (PathPattern pattern : PRIVATE) {
            if (pattern.matches(path)) {
                return true;
            }
        }
        return false;
    }

    private static List<PathPattern> parse(String... patterns) {
        PathPatternParser parser = new PathPatternParser();
        parser.setCaseSensitive(false);
        return Arrays.stream(patterns).map(parser::parse).toList();
    }
}
