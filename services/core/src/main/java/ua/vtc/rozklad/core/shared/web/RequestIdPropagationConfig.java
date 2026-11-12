package ua.vtc.rozklad.core.shared.web;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.http.client.ClientHttpRequestInterceptor;

/**
 * Keeps the request id ({@link RequestIdFilter}) with the work it caused: every RestClient built from
 * the application's {@code RestClient.Builder} (the accounts API of auth) forwards {@code X-Request-Id},
 * and tasks of the application executor (async Modulith listeners, the outbox externalization) run with
 * the MDC of the thread that submitted them.
 */
@Configuration(proxyBeanMethods = false)
public class RequestIdPropagationConfig {

    @Bean
    RestClientCustomizer requestIdRestClientCustomizer() {
        ClientHttpRequestInterceptor interceptor = (request, body, execution) -> {
            String requestId = MDC.get(RequestIdFilter.MDC_KEY);
            if (requestId != null && !request.getHeaders().containsHeader(RequestIdFilter.HEADER)) {
                request.getHeaders().set(RequestIdFilter.HEADER, requestId);
            }
            return execution.execute(request, body);
        };
        return builder -> builder.requestInterceptor(interceptor);
    }

    /** Picked up by Boot's {@code applicationTaskExecutor}, which runs {@code @Async} methods. */
    @Bean
    TaskDecorator mdcTaskDecorator() {
        return task -> {
            Map<String, String> submitted = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> own = MDC.getCopyOfContextMap();
                setContext(submitted);
                try {
                    task.run();
                } finally {
                    setContext(own);
                }
            };
        };
    }

    private static void setContext(Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
