package ua.vtc.rozklad.notification.core;

import java.util.function.Supplier;
import org.springframework.http.ProblemDetail;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Translates RestClient failures into {@link UpstreamUnavailableException} / {@link UpstreamRejectedException}. */
final class UpstreamCalls {

    private UpstreamCalls() {
    }

    static <T> T call(String service, String operation, Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (e.getStatusCode().is4xxClientError()) {
                throw new UpstreamRejectedException(status, detail(e));
            }
            throw new UpstreamUnavailableException(service, operation + ": HTTP " + status, e);
        } catch (ResourceAccessException e) {
            throw new UpstreamUnavailableException(service, operation + ": " + e.getMessage(), e);
        } catch (OAuth2AuthorizationException e) {
            // не вдалося отримати токен сервісного акаунта (Keycloak недоступний або невірний секрет)
            throw new UpstreamUnavailableException(service, operation + ": no service token ("
                    + e.getError().getErrorCode() + ")", e);
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException(service, operation + ": " + e.getMessage(), e);
        }
    }

    static void run(String service, String operation, Runnable call) {
        call(service, operation, () -> {
            call.run();
            return null;
        });
    }

    private static String detail(RestClientResponseException e) {
        try {
            ProblemDetail problem = e.getResponseBodyAs(ProblemDetail.class);
            return problem == null ? null : problem.getDetail();
        } catch (RuntimeException notAProblem) {
            return null;
        }
    }
}
