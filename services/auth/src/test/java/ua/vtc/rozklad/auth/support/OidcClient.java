package ua.vtc.rozklad.auth.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** What the SPA does: authorization code with PKCE (S256) for {@code rozklad-web}, then the token request. */
public final class OidcClient {

    public static final String WEB = "rozklad-web";
    public static final String WEB_CALLBACK = "http://localhost:5180/login/callback";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private OidcClient() {
    }

    public static String verifier() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static String authorizeUrl(String verifier, String state, String extra) {
        return UriComponentsBuilder.fromPath("/auth/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", WEB)
                .queryParam("redirect_uri", WEB_CALLBACK)
                .queryParam("scope", "openid profile email")
                .queryParam("state", state)
                .queryParam("nonce", "n-" + state)
                .queryParam("code_challenge", challenge(verifier))
                .queryParam("code_challenge_method", "S256")
                .build().encode().toUriString() + (extra == null ? "" : "&" + extra);
    }

    /** Signs in through the full flow and returns the token response. */
    public static Tokens signIn(Browser browser, String username, String password) {
        String verifier = verifier();
        HttpResponse<String> authorize = browser.get(authorizeUrl(verifier, "s1", null), "Accept", "text/html");
        assertThat(authorize.statusCode()).isEqualTo(302);
        HttpResponse<String> afterLogin = browser.signIn(username, password);
        HttpResponse<String> callback = browser.follow(afterLogin);
        String location = Browser.location(callback);
        assertThat(location).startsWith(WEB_CALLBACK);
        Map<String, String> params = Browser.query(location);
        assertThat(params).containsEntry("state", "s1").containsKey("code");
        return exchange(browser, params.get("code"), verifier);
    }

    public static Tokens exchange(Browser browser, String code, String verifier) {
        HttpResponse<String> response = tokenRequest(browser, code, verifier);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return new Tokens(JSON.readTree(response.body()));
    }

    public static HttpResponse<String> tokenRequest(Browser browser, String code, String verifier) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("client_id", WEB);
        form.put("code", code);
        form.put("redirect_uri", WEB_CALLBACK);
        form.put("code_verifier", verifier);
        return browser.postForm("/auth/oauth2/token", form);
    }

    public static JsonNode json(String body) {
        return JSON.readTree(body);
    }

    /** Decoded JWT parts (no signature check: tests that need it use the JWKS). */
    public static JsonNode jwtPart(String jwt, int index) {
        String part = jwt.split("\\.")[index];
        return JSON.readTree(new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8));
    }

    public record Tokens(JsonNode body) {

        public String accessToken() {
            return body.path("access_token").asString();
        }

        public String idToken() {
            return body.path("id_token").asString();
        }

        public JsonNode accessClaims() {
            return jwtPart(accessToken(), 1);
        }

        public JsonNode accessHeader() {
            return jwtPart(accessToken(), 0);
        }

        public JsonNode idClaims() {
            return jwtPart(idToken(), 1);
        }
    }
}
