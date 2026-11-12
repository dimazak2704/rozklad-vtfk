package ua.vtc.rozklad.scheduler.messaging;

import java.util.Map;
import java.util.regex.Pattern;
import org.aopalliance.intercept.MethodInterceptor;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import ua.vtc.rozklad.scheduler.web.RequestIdFilter;

/**
 * The request id across RabbitMQ (CONTRACTS.md §7, message properties): a sent event carries the
 * {@code X-Request-Id} of the sending thread's MDC as a header and its {@code eventId} as the AMQP
 * {@code message_id}; a listener runs with both in the MDC ({@code requestId}, {@code eventId}), retries
 * included, so its log lines and the calls it makes link back to the request that caused the event. A
 * message without a safe header gets a new request id, like an HTTP request without one.
 */
public final class RequestIdMessages {

    public static final String HEADER = RequestIdFilter.HEADER;
    public static final String EVENT_ID_MDC_KEY = "eventId";

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private RequestIdMessages() {
    }

    /** Before sending: the event id and, inside a request (or a listener), its request id. */
    public static void stamp(MessageProperties properties, String eventId) {
        properties.setMessageId(eventId);
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null && properties.getHeader(HEADER) == null) {
            properties.setHeader(HEADER, requestId);
        }
    }

    /**
     * Advice of the listener container ({@code invokeListener(Channel, Object data)}): the outermost one,
     * so retries and the recoverer log with the same ids.
     */
    public static MethodInterceptor listenerMdc() {
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
