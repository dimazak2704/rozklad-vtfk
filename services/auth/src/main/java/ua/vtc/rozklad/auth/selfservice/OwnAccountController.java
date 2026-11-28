package ua.vtc.rozklad.auth.selfservice;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.auth.login.ClientAddress;
import ua.vtc.rozklad.auth.session.GoogleSignInMark;

/**
 * The person's own account, called by the SPA with its access token ({@link OwnAccountSecurityConfig}):
 * {@code GET /api/account/password}, {@code POST /api/account/password} and, for an account that signs in with
 * Google only, {@code POST /api/account/initial-password}. Refusals are RFC 9457 problems with a
 * stable {@code type} ({@code urn:rozklad:auth:<code>}), the same {@code code} as a property and a Ukrainian
 * {@code detail}; {@code password-rejected} also carries {@code reason}.
 */
@RestController
@RequestMapping("/api/account")
class OwnAccountController {

    static final String PROBLEM_BASE = "urn:rozklad:auth:";

    private final OwnPasswordService passwords;

    OwnAccountController(OwnPasswordService passwords) {
        this.passwords = passwords;
    }

    @GetMapping("/password")
    OwnPasswordService.Info password(@AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        return passwords.info(accountId(jwt), GoogleSignInMark.of(http.getSession(false)));
    }

    @PostMapping("/password")
    ResponseEntity<Void> changePassword(@AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) ChangeRequest request, HttpServletRequest http) {
        ChangeRequest body = request == null ? new ChangeRequest(null, null) : request;
        // cookie сесії входу (шлях /auth) приходить разом із запитом: цю сесію лишаємо, інші завершуємо
        HttpSession session = http.getSession(false);
        passwords.change(accountId(jwt), body.currentPassword(), body.newPassword(), ClientAddress.of(http),
                session == null ? null : session.getId());
        return ResponseEntity.noContent().build();
    }

    /**
     * The first password of a Google-only account. Besides the token, the sign-in cookie of this browser must come
     * along, and its session must have finished a Google sign-in of the same account shortly before
     * ({@link OwnPasswordService#setInitial}); the session itself stays, the proof is used up.
     */
    @PostMapping("/initial-password")
    ResponseEntity<Void> setInitialPassword(@AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) InitialRequest request, HttpServletRequest http) {
        InitialRequest body = request == null ? new InitialRequest(null) : request;
        HttpSession session = http.getSession(false);
        passwords.setInitial(accountId(jwt), body.newPassword(), ClientAddress.of(http),
                GoogleSignInMark.of(session), session == null ? null : session.getId());
        GoogleSignInMark.clear(session);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(OwnPasswordRefusal.class)
    ResponseEntity<ProblemDetail> refused(OwnPasswordRefusal refused) {
        OwnPasswordRefusal.Problem kind = refused.problem();
        HttpStatus status = HttpStatus.valueOf(kind.status());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, refused.getMessage());
        problem.setType(URI.create(PROBLEM_BASE + kind.code()));
        problem.setProperty("code", kind.code());
        if (refused.reason() != null) {
            problem.setProperty("reason", refused.reason());
        }
        return ResponseEntity.status(status).body(problem);
    }

    /** The chain lets only tokens whose {@code sub} is an account id through ({@link OwnAccountSecurityConfig}). */
    private static UUID accountId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    /** {@code {newPassword}}; never logged. */
    record InitialRequest(String newPassword) {

        @Override
        public String toString() {
            return "InitialRequest[newPassword=***]";
        }
    }

    /** {@code {currentPassword, newPassword}}; never logged. */
    record ChangeRequest(String currentPassword, String newPassword) {

        @Override
        public String toString() {
            return "ChangeRequest[currentPassword=***, newPassword=***]";
        }
    }
}
