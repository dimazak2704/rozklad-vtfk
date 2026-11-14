package ua.vtc.rozklad.notification.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import ua.vtc.rozklad.notification.config.TelegramProperties;
import ua.vtc.rozklad.notification.config.TelegramProperties.Access;
import ua.vtc.rozklad.notification.config.TelegramProperties.Mode;
import ua.vtc.rozklad.notification.web.FieldValidationException;
import ua.vtc.rozklad.notification.web.ForbiddenOperationException;
import ua.vtc.rozklad.notification.web.NotFoundException;

/** An anonymous caller of the open webhook path cannot make the service read its body. */
class TelegramWebhookControllerTest {

    private static final String SECRET = "webhook-secret";

    @Test
    void inPollingModeTheBodyIsNotRead() {
        UnreadBody request = new UnreadBody();

        assertThatThrownBy(() -> controller(Mode.POLLING).receive(SECRET, request))
                .isInstanceOf(NotFoundException.class);
        assertThat(request.read).isFalse();
    }

    @Test
    void withoutTheRightSecretTheBodyIsNotRead() {
        UnreadBody request = new UnreadBody();

        assertThatThrownBy(() -> controller(Mode.WEBHOOK).receive("wrong", request))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> controller(Mode.WEBHOOK).receive(null, request))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThat(request.read).isFalse();
    }

    @Test
    void aDeclaredLengthAboveTheLimitIsRefusedUnread() {
        UnreadBody request = new UnreadBody();
        request.declaredLength = TelegramWebhookController.MAX_UPDATE_BYTES + 1;

        assertThatThrownBy(() -> controller(Mode.WEBHOOK).receive(SECRET, request))
                .isInstanceOf(FieldValidationException.class)
                .hasMessage("Оновлення Telegram завелике.");
        assertThat(request.read).isFalse();
    }

    @Test
    void aStreamedBodyIsCutAtTheLimit() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/notify/telegram/webhook");
        // без Content-Length (chunked): читаємо не більше за межу
        request.setContent(new byte[TelegramWebhookController.MAX_UPDATE_BYTES + 10]);

        assertThatThrownBy(() -> controller(Mode.WEBHOOK).receive(SECRET, chunked(request)))
                .isInstanceOf(FieldValidationException.class)
                .hasMessage("Оновлення Telegram завелике.");
    }

    private static TelegramWebhookController controller(Mode mode) {
        return new TelegramWebhookController(new TelegramProperties("123:token", "scheduler_vtfk_bot", null, mode,
                SECRET, null, Access.BOUND_ONLY), null);
    }

    /** A request whose content length is unknown, as with chunked transfer encoding. */
    private static MockHttpServletRequest chunked(MockHttpServletRequest request) {
        return new MockHttpServletRequest() {
            @Override
            public ServletInputStream getInputStream() {
                return request.getInputStream();
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
    }

    /** Fails the test's expectation if anything reads the body. */
    private static final class UnreadBody extends MockHttpServletRequest {

        private boolean read;
        private long declaredLength = -1;

        UnreadBody() {
            super("POST", "/api/notify/telegram/webhook");
        }

        @Override
        public long getContentLengthLong() {
            return declaredLength;
        }

        @Override
        public ServletInputStream getInputStream() {
            read = true;
            return new ServletInputStream() {
                @Override
                public int read() {
                    return -1;
                }

                @Override
                public boolean isFinished() {
                    return true;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                }
            };
        }
    }
}
