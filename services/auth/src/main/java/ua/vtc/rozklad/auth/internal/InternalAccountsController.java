package ua.vtc.rozklad.auth.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.auth.account.Roles;

/**
 * Read side of the internal accounts API (DESIGN §1.3). Lookups by id, username, e-mail or a list of ids,
 * and a filtered page for «Доступи». Writes arrive in a later step.
 */
@RestController
@RequestMapping("/internal/accounts")
public class InternalAccountsController {

    static final String PROBLEM_BASE = "urn:rozklad:auth:";
    private static final int MAX_PAGE_SIZE = 200;

    private final AccountQueries queries;

    public InternalAccountsController(AccountQueries queries) {
        this.queries = queries;
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

    static ResponseEntity<ProblemDetail> problem(HttpStatus status, String type, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(java.net.URI.create(PROBLEM_BASE + type));
        return ResponseEntity.status(status).body(problem);
    }
}
