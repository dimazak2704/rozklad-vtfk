package ua.vtc.rozklad.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Which authorize requests skip the sign-in redirect so Spring AS can answer {@code login_required}. */
class SilentAuthorizeRequestTest {

    @Test
    void onlyAuthorizeWithPromptNoneMatches() {
        assertThat(AuthorizationServerConfig.hasPromptNone("none")).isTrue();
        assertThat(AuthorizationServerConfig.hasPromptNone(" none ")).isTrue();
        assertThat(AuthorizationServerConfig.hasPromptNone("login")).isFalse();
        assertThat(AuthorizationServerConfig.hasPromptNone("nonexistent")).isFalse();
        assertThat(AuthorizationServerConfig.hasPromptNone(null)).isFalse();

        var matcher = AuthorizationServerConfig.silentAuthorizeRequest();
        assertThat(matcher.matches(request("/oauth2/authorize", "none"))).isTrue();
        assertThat(matcher.matches(request("/oauth2/authorize", null))).isFalse();
        assertThat(matcher.matches(request("/oauth2/token", "none"))).isFalse();
    }

    private static MockHttpServletRequest request(String path, String prompt) {
        var request = new MockHttpServletRequest("GET", "/auth" + path);
        request.setContextPath("/auth");
        request.setServletPath(path);
        if (prompt != null) {
            request.setParameter("prompt", prompt);
        }
        return request;
    }
}
