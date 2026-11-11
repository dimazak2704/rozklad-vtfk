package ua.vtc.rozklad.auth.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * A minimal browser over {@link HttpClient}: a cookie jar, no automatic redirects, form posts, and the
 * {@code X-Forwarded-For} a proxy would send (Tomcat trusts it from 127.0.0.1).
 */
public class Browser {

    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"|value=\"([^\"]+)\"\\s+name=\"_csrf\"");

    private final String origin;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient client;
    private String forwardedFor;

    Browser(String origin) {
        this.origin = origin;
        this.client = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    public String origin() {
        return origin;
    }

    public void forwardedFor(String clientIp) {
        this.forwardedFor = clientIp;
    }

    /** GET of a path under the origin ({@code /auth/...}) or an absolute URL of the same server. */
    public HttpResponse<String> get(String pathOrUrl, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(pathOrUrl)).GET();
        return send(request, headers);
    }

    public HttpResponse<String> postForm(String path, Map<String, String> form, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encode(form)));
        return send(request, headers);
    }

    /** Opens the sign-in page and posts the form with its CSRF token. */
    public HttpResponse<String> signIn(String username, String password) {
        HttpResponse<String> page = get("/auth/login", "Accept", "text/html");
        assertThat(page.statusCode()).as("sign-in page").isEqualTo(200);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("username", username);
        form.put("password", password);
        form.put("_csrf", csrf(page.body()));
        return postForm("/auth/login", form);
    }

    /** Follows redirects that stay on this server; stops at the first one leaving it (e.g. the SPA callback). */
    public HttpResponse<String> follow(HttpResponse<String> response) {
        HttpResponse<String> current = response;
        for (int hops = 0; hops < 10 && isRedirect(current); hops++) {
            URI target = current.uri().resolve(location(current));
            if (!target.toString().startsWith(origin)) {
                return current;
            }
            current = get(target.toString(), "Accept", "text/html");
        }
        return current;
    }

    public Optional<HttpCookie> cookie(String name) {
        return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals(name)).findFirst();
    }

    public void clearCookies() {
        cookies.getCookieStore().removeAll();
    }

    public static String csrf(String html) {
        Matcher matcher = CSRF.matcher(html);
        assertThat(matcher.find()).as("hidden _csrf input").isTrue();
        return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
    }

    public static boolean isRedirect(HttpResponse<?> response) {
        return response.statusCode() >= 300 && response.statusCode() < 400;
    }

    public static String location(HttpResponse<?> response) {
        return response.headers().firstValue("Location").orElseThrow(() ->
                new AssertionError("no Location header, status " + response.statusCode()));
    }

    /** Query parameters of a redirect target (decoded). */
    public static Map<String, String> query(String url) {
        return UriComponentsBuilder.fromUriString(url).build().getQueryParams().toSingleValueMap().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> e.getValue() == null ? "" : java.net.URLDecoder.decode(e.getValue(), StandardCharsets.UTF_8)));
    }

    public static String encode(Map<String, String> form) {
        return form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private URI uri(String pathOrUrl) {
        return URI.create(pathOrUrl.startsWith("http") ? pathOrUrl : origin + pathOrUrl);
    }

    private HttpResponse<String> send(HttpRequest.Builder request, String... headers) {
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        try {
            return client.send(request.timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    /** All {@code Set-Cookie} headers of a response. */
    public static List<String> setCookies(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie");
    }
}
