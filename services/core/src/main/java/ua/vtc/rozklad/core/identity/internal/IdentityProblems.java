package ua.vtc.rozklad.core.identity.internal;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import ua.vtc.rozklad.core.identity.InviteStatus;

/**
 * Identity errors with statuses the shared handler has no exception for (410, 503, a 403 with a
 * specific reason). They are {@link ErrorResponseException}s, so the shared handler renders the
 * prepared ProblemDetail and adds {@code requestId}.
 */
public final class IdentityProblems {

    private IdentityProblems() {
    }

    /** 410: the invite exists but cannot be used any more. */
    public static ErrorResponseException inviteGone(InviteStatus status) {
        String detail = switch (status) {
            case USED -> "Запрошення вже використано. Увійдіть зі своїм ім'ям користувача або попросіть нове запрошення.";
            case EXPIRED -> "Термін дії запрошення минув. Попросіть адміністратора надіслати нове.";
            case REVOKED -> "Запрошення відкликано: адміністратор надіслав новіше.";
            case ACTIVE -> throw new IllegalArgumentException("an active invite is not gone");
        };
        ProblemDetail body = problem(HttpStatus.GONE, "invite-gone", "Запрошення недійсне", detail);
        body.setProperty("status", status);
        return new ErrorResponseException(HttpStatus.GONE, body, null);
    }

    /** 503: Keycloak is unreachable or rejected the service account. */
    public static ErrorResponseException identityUnavailable(Throwable cause) {
        return new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE, problem(HttpStatus.SERVICE_UNAVAILABLE,
                "identity-unavailable", "Сервіс входу недоступний",
                "Не вдалося створити обліковий запис: сервер автентифікації не відповідає. Спробуйте пізніше."), cause);
    }

    /** 403: a TEACHER token whose account is not linked to any teacher of the catalog. */
    public static ErrorResponseException accountNotLinked() {
        return new ErrorResponseException(HttpStatus.FORBIDDEN, problem(HttpStatus.FORBIDDEN, "forbidden",
                "Доступ заборонено", "Ваш обліковий запис не прив'язано до викладача. Зверніться до адміністратора."),
                null);
    }

    private static ProblemDetail problem(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(URI.create("urn:rozklad:problem:" + slug));
        body.setTitle(title);
        return body;
    }
}
