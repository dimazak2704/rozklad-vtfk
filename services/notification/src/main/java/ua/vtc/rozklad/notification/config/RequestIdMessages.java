package ua.vtc.rozklad.notification.config;

import java.util.Map;
import java.util.regex.Pattern;
import org.aopalliance.intercept.MethodInterceptor;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import ua.vtc.rozklad.notification.web.RequestIdFilter;

/**
 * The request id across RabbitMQ (CONTRACTS.md §7, message properties): core sends an event with the
 * {@code X-Request-Id} of the request that caused it and its {@code eventId} as the AMQP {@code message_id};
 * the listener runs with both in the MDC ({@code requestId}, {@code eventId}), retries included, so its log
 * lines and its calls to core and importer ({@link ServiceClientsConfig}) link back to that request. A
 * message without a safe header gets a new request id, like an HTTP request without one.
 */
public final class RequestIdMessages {

    public static final String HEADER = RequestIdFilter.HEADER;
    public static final String EVENT_ID_MDC_KEY = "eventId";

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private RequestIdMessages() {
    }

    /**
     * Advice of the listener container ({@code invokeListener(Channel, Object data)}): the outermost one,
     * so retries and the recoverer log with the same ids.
     */
    static MethodInterceptor listenerMdc() {
        return invocation -> {
            Object[] arguments = invocation.getArguments();
            if (arguments.length < 2 || !(arguments[1] instanceof Message message)) {
                return invocation.proceed();
            }
            Map<String, String> own = MDC.getCopyOfContextMap();
            MessageProperties properties = message.getMessageProperties();
            Object header = properties.getHeader(HEADER);
            MDC.put(RequestIdFilter.MDC_KEY, RequestIdFilter.resolve(header == null ? null : header.toString()));
            String eventId = properties.getMessageId();
            if (eventId != null && SAFE_ID.matcher(eventId).matches()) {
                MDC.put(EVENT_ID_MDC_KEY, eventId);
            }
            try {
                return invocation.proceed();
            } finally {
                if (own == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(own);
                }
            }
        };
    }
}
