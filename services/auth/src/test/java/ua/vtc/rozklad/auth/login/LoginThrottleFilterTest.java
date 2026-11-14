package ua.vtc.rozklad.auth.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import ua.vtc.rozklad.auth.support.PropertiesFixture;

/**
 * What {@link LoginThrottleFilter} lets through to the password check when the lockout rules allow the attempt:
 * only an identifier that some account could have. The rest fail at once, without BCrypt.
 */
class LoginThrottleFilterTest {

    private static final Instant NOW = Instant.parse("2026-11-20T08:00:00Z");

    private final LoginAttempts attempts = mock(LoginAttempts.class);
    private final LoginDevices devices = mock(LoginDevices.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final LoginThrottleFilter filter = new LoginThrottleFilter(attempts, devices,
            PropertiesFixture.dev().lockout(), Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void nothingIsLocked() {
        when(devices.trusted(any(), any(), any())).thenReturn(Optional.empty());
        when(attempts.stats(any(), any(), any(), any(), any()))
                .thenReturn(new LockoutPolicy.Stats(0, null, 0, 0, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"kren\u0001tsin", "kren\u0000tsin", "kren\u001btsin", "kren\u007ftsin", "kren\u0085tsin"})
    void anIdentifierWithAControlCharacterInsideNeverReachesThePasswordCheck(String username) throws Exception {
        MockHttpServletResponse response = post(username);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
        verify(attempts).record(eq(LoginAttempts.Method.PASSWORD), aryEq(LoginAttempts.hash(username)), isNull(),
                any(), isNull(), eq(LoginAttempts.Outcome.BAD_CREDENTIALS), eq(NOW));
    }

    @Test
    void anIdentifierLongerThanAnyAccountsNeverReachesThePasswordCheck() throws Exception {
        MockHttpServletResponse response = post("a".repeat(250) + "@x.ua");

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
    }

    @Test
    void aBlankIdentifierNeverReachesThePasswordCheck() throws Exception {
        assertThat(post("  ").getRedirectedUrl()).isEqualTo("/login?error");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void controlCharactersAtTheEdgesAreTrimmedLikeTheFormLoginDoes() throws Exception {
        MockHttpServletRequest request = request("\u0001Krentsin\u001b");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(attempts).stats(aryEq(LoginAttempts.hash("krentsin")), any(), any(), any(), any());
    }

    @Test
    void usernamesAndEmailsGoOnToThePasswordCheck() throws Exception {
        for (String username : new String[] {"krentsin", "teacher@vtc.vn.ua", "a".repeat(244) + "@vtc.vn.ua"}) {
            MockHttpServletRequest request = request(username);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
        }
    }

    private MockHttpServletResponse post(String username) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(username), response, chain);
        return response;
    }

    private static MockHttpServletRequest request(String username) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setRemoteAddr("198.51.100.7");
        request.addParameter("username", username);
        request.addParameter("password", "anything");
        return request;
    }
}
