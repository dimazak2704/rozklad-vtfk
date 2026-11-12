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

    /**
     * 410: the invite exists but cannot be used any more. Its state is the extension member
     * {@code inviteStatus}; the standard {@code status} stays the HTTP status (RFC 9457).
     */
    public static ErrorResponseException inviteGone(InviteStatus status) {
        String detail = switch (status) {
            case USED -> "Запрошення вже використано. Увійдіть зі своїм ім'ям користувача або попросіть нове запрошення.";
            case EXPIRED -> "Термін дії запрошення минув. Попросіть адміністратора надіслати нове.";
            case REVOKED -> "Запрошення відкликано: адміністратор надіслав новіше.";
            case ACTIVE -> throw new IllegalArgumentException("an active invite is not gone");
        };
        ProblemDetail body = problem(HttpStatus.GONE, "invite-gone", "Запрошення недійсне", detail);
        body.setProperty("inviteStatus", status);
        return new ErrorResponseException(HttpStatus.GONE, body, null);
    }

    /**
     * 410 for an invite whose teacher's account is revoked (DESIGN D11): only an admin's explicit restore gives
     * access back. {@code inviteStatus} is {@code REVOKED}, the invite is revoked as well.
     */
    public static AccessRevokedException accessRevoked() {
        ProblemDetail body = problem(HttpStatus.GONE, "invite-gone", "Доступ відкликано",
                "Доступ відкликано. Зверніться до навчальної частини.");
        body.setProperty("inviteStatus", InviteStatus.REVOKED);
        return new AccessRevokedException(body);
    }

    /** 503: the sign-in service is unreachable or rejected the service token. */
    public static ErrorResponseException identityUnavailable(Throwable cause) {
        return new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE, problem(HttpStatus.SERVICE_UNAVAILABLE,
                "identity-unavailable", "Сервіс входу недоступний",
                "Не вдалося створити обліковий запис: сервер автентифікації не відповідає. Спробуйте пізніше."), cause);
    }

    /** 503 while reading accounts (linking a teacher to an existing account). */
    public static ErrorResponseException identityLookupUnavailable(Throwable cause) {
        return new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE, problem(HttpStatus.SERVICE_UNAVAILABLE,
                "identity-unavailable", "Сервіс входу недоступний",
                "Не вдалося перевірити обліковий запис: сервер автентифікації не відповідає. Спробуйте пізніше."), cause);
    }

    /** 503 while changing an access grant in «Доступи». */
    public static ErrorResponseException identityChangeUnavailable(Throwable cause) {
        return new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE, problem(HttpStatus.SERVICE_UNAVAILABLE,
                "identity-unavailable", "Сервіс входу недоступний",
                "Не вдалося змінити доступ: сервер автентифікації не відповідає. Спробуйте пізніше."), cause);
    }

    /**
     * 409 of «Доступи» with a stable {@code code} ({@code LAST_ADMIN}, {@code EMAIL_TAKEN},
     * {@code TEACHER_ALREADY_LINKED}, {@code ACCOUNT_REVOKED}) and a Ukrainian message for the page.
     */
    public static ErrorResponseException accessConflict(String code, String detail) {
        ProblemDetail body = problem(HttpStatus.CONFLICT, "conflict", "Конфлікт", detail);
        body.setProperty("code", code);
        return new ErrorResponseException(HttpStatus.CONFLICT, body, null);
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

    /** {@link #accessRevoked()}; its own type so the invite's revocation is committed, not rolled back. */
    public static final class AccessRevokedException extends ErrorResponseException {

        private AccessRevokedException(ProblemDetail body) {
            super(HttpStatus.GONE, body, null);
        }
    }
}
