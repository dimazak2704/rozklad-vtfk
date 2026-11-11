package ua.vtc.rozklad.auth.internal;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.auth.account.AccountAdministration;
import ua.vtc.rozklad.auth.account.AccountRuleException;
import ua.vtc.rozklad.auth.account.Roles;

/**
 * The internal accounts API for core (DESIGN §1.3): lookups by id, username, e-mail or a list of ids, a
 * filtered page for «Доступи», and the writes behind invites, password links and access grants. Errors are
 * RFC 9457 problems with a stable {@code type} ({@code urn:rozklad:auth:<code>}); {@code password-rejected}
 * adds {@code reason}. {@code X-Actor} names the admin for the audit columns.
 */
@RestController
@RequestMapping("/internal/accounts")
public class InternalAccountsController {

    static final String PROBLEM_BASE = "urn:rozklad:auth:";
    private static final int MAX_PAGE_SIZE = 200;

    static final String ACTOR = "X-Actor";

    private final AccountQueries queries;
    private final AccountAdministration administration;

    public InternalAccountsController(AccountQueries queries, AccountAdministration administration) {
        this.queries = queries;
        this.administration = administration;
    }

    @GetMapping("/{id}")
    ResponseEntity<?> byId(@PathVariable UUID id) {
        return queries.byId(id).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> problem(HttpStatus.NOT_FOUND, "not-found", "Account not found"));
    }

    /** Exactly one of {@code username}, {@code email}, {@code ids}; otherwise a filtered page. */
    @GetMapping
    ResponseEntity<?> find(@RequestParam Optional<String> username, @RequestParam Optional<String> email,
            @RequestParam Optional<List<UUID>> ids, @RequestParam Optional<String> q,
            @RequestParam Optional<String> role, @RequestParam Optional<Boolean> enabled,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        if (username.isPresent()) {
            return ResponseEntity.ok(queries.byUsername(username.get()).stream().toList());
        }
        if (email.isPresent()) {
            return ResponseEntity.ok(queries.byEmail(email.get()).stream().toList());
        }
        if (ids.isPresent()) {
            return ResponseEntity.ok(queries.byIds(ids.get()));
        }
        if (role.isPresent() && !Roles.HUMAN.contains(role.get())) {
            return problem(HttpStatus.BAD_REQUEST, "validation", "Unknown role");
        }
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            return problem(HttpStatus.BAD_REQUEST, "validation", "page >= 0, 1 <= size <= " + MAX_PAGE_SIZE);
        }
        return ResponseEntity.ok(queries.search(q.orElse(null), role.orElse(null), enabled.orElse(null), page, size));
    }

    /** Idempotent create with the id chosen by core: 201 created, 200 when the id already exists (unchanged). */
    @PutMapping("/{id}")
    ResponseEntity<AccountDto> create(@PathVariable UUID id, @RequestBody CreateRequest request,
            @RequestHeader(name = ACTOR, required = false) String actor) {
        boolean created = administration.create(id, new AccountAdministration.NewAccount(request.username(),
                request.email(), request.displayName(), request.roles(), request.password(),
                request.enabled() == null || request.enabled()), actor);
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).body(current(id));
    }

    @PatchMapping("/{id}")
    AccountDto update(@PathVariable UUID id, @RequestBody AccountAdministration.AccountPatch patch,
            @RequestHeader(name = ACTOR, required = false) String actor) {
        administration.update(id, patch, actor);
        return current(id);
    }

    @PutMapping("/{id}/password")
    ResponseEntity<Void> setPassword(@PathVariable UUID id, @RequestBody PasswordRequest request,
            @RequestHeader(name = ACTOR, required = false) String actor) {
        administration.setPassword(id, request.password(), actor);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/revoke")
    AccountDto revoke(@PathVariable UUID id, @RequestHeader(name = ACTOR, required = false) String actor) {
        administration.revoke(id, actor);
        return current(id);
    }

    @PostMapping("/{id}/enable")
    AccountDto enable(@PathVariable UUID id, @RequestBody(required = false) EnableRequest request,
            @RequestHeader(name = ACTOR, required = false) String actor) {
        administration.enable(id, request != null && request.clearRevocation(), actor);
        return current(id);
    }

    @DeleteMapping("/{id}/federated/google")
    ResponseEntity<Void> unbindGoogle(@PathVariable UUID id,
            @RequestHeader(name = ACTOR, required = false) String actor) {
        administration.unbindGoogle(id, actor);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id, @RequestHeader(name = ACTOR, required = false) String actor) {
        administration.delete(id, actor);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(AccountRuleException.class)
    ResponseEntity<ProblemDetail> refused(AccountRuleException refused) {
        ResponseEntity<ProblemDetail> response = problem(HttpStatus.valueOf(refused.rule().status()),
                refused.rule().type(), refused.getMessage());
        if (refused.reason() != null) {
            response.getBody().setProperty("reason", refused.reason());
        }
        return response;
    }

    private AccountDto current(UUID id) {
        return queries.byId(id).orElseThrow(() -> new AccountRuleException(AccountRuleException.Rule.NOT_FOUND,
                "Account not found"));
    }

    static ResponseEntity<ProblemDetail> problem(HttpStatus status, String type, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(java.net.URI.create(PROBLEM_BASE + type));
        return ResponseEntity.status(status).body(problem);
    }

    /** {@code enabled} defaults to {@code true}. */
    record CreateRequest(String username, String email, String displayName, Set<String> roles, String password,
            Boolean enabled) {

        @Override
        public String toString() {
            return "CreateRequest[username=" + username + ", email=" + email + ", password=***]";
        }
    }

    record PasswordRequest(String password) {

        @Override
        public String toString() {
            return "PasswordRequest[password=***]";
        }
    }

    record EnableRequest(boolean clearRevocation) {
    }
}
