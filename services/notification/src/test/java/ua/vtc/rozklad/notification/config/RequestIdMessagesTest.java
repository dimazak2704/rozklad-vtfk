package ua.vtc.rozklad.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import ua.vtc.rozklad.notification.web.RequestIdFilter;

/** X-Request-Id and the event id of a consumed message are in the listener's MDC. */
class RequestIdMessagesTest {

    private final MethodInterceptor advice = RequestIdMessages.listenerMdc();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void theListenerRunsWithTheRequestIdAndEventIdOfTheMessageAndRestoresTheThreadAfterwards() throws Throwable {
        MDC.put("container", "own");
        Map<String, String> seen = new HashMap<>();

        advice.invoke(invocation(message("req-5", "e-5"), () -> seen.putAll(MDC.getCopyOfContextMap())));

        assertThat(seen).containsEntry(RequestIdFilter.MDC_KEY, "req-5")
                .containsEntry(RequestIdMessages.EVENT_ID_MDC_KEY, "e-5");
        assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of("container", "own"));
    }

    @Test
    void aMessageWithoutASafeRequestIdGetsANewOne() throws Throwable {
        Map<String, String> seen = new HashMap<>();

        advice.invoke(invocation(message("forged\nINFO line", null), () -> seen.putAll(MDC.getCopyOfContextMap())));

        assertThat(UUID.fromString(seen.get(RequestIdFilter.MDC_KEY))).isNotNull();
        assertThat(seen).doesNotContainKey(RequestIdMessages.EVENT_ID_MDC_KEY);
    }

    @Test
    void theMdcIsRestoredWhenTheListenerFails() {
        assertThatThrownBy(() -> advice.invoke(invocation(message("req-6", "e-6"), () -> {
            throw new IllegalStateException("handler failed");
        }))).hasMessage("handler failed");

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    private static Message message(String requestId, String eventId) {
        MessageProperties properties = new MessageProperties();
        properties.setHeader(RequestIdMessages.HEADER, requestId);
        properties.setMessageId(eventId);
        return new Message(new byte[0], properties);
    }

    /** The container advises {@code invokeListener(Channel, Object data)}. */
    private static MethodInvocation invocation(Message message, Runnable listener) {
        return new MethodInvocation() {
            @Override
            public Method getMethod() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Object[] getArguments() {
                return new Object[] {null, message};
            }

            @Override
            public Object proceed() {
                listener.run();
                return null;
            }

            @Override
            public Object getThis() {
                return null;
            }

            @Override
            public AccessibleObject getStaticPart() {
                throw new UnsupportedOperationException();
            }
        };
    }
}
