package ua.vtc.rozklad.core.substitution.internal.change;

import jakarta.validation.Valid;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.core.shared.security.CurrentUser;

/** Changes (заміни) for the dispatcher (CONTRACTS §2 «Substitutions»). */
@RestController
@RequestMapping("/api/admin/changes")
class AdminChangeController {

    private final ChangeCommands commands;
    private final ChangeQueries queries;
    private final RecommendationLog recommendations;
    private final Clock clock;

    AdminChangeController(ChangeCommands commands, ChangeQueries queries, RecommendationLog recommendations,
                          Clock clock) {
        this.commands = commands;
        this.queries = queries;
        this.recommendations = recommendations;
        this.clock = clock;
    }

    /** Changes of a date (today by default), optionally of one status. */
    @GetMapping
    List<ChangeDto> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                         @RequestParam(required = false) ChangeStatus status) {
        return queries.forDate(date != null ? date : LocalDate.now(clock), status);
    }

    @GetMapping("/{id}")
    ChangeDto get(@PathVariable UUID id) {
        return queries.get(id);
    }

    /** 201 with the status after the approval rules; 409 with {@code conflicts[]} when the slot is busy. */
    @PostMapping
    ResponseEntity<ChangeDto> create(@Valid @RequestBody CreateChangeRequest request, CurrentUser user) {
        RecommendationLog.Shown shown = recommendations.before(request);
        ChangeDto created = commands.create(request, user.username());
        recommendations.after(shown, request, created);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * 201 with the created changes in request order. One transaction: a 400 or 409 for any of them
     * creates none (e.g. a cancel with its make-up pair, every proposal of a day plan).
     */
    @PostMapping("/batch")
    ResponseEntity<List<ChangeDto>> createAll(@Valid @RequestBody BatchChangesRequest request, CurrentUser user) {
        List<RecommendationLog.Shown> shown = request.changes().stream().map(recommendations::before).toList();
        List<ChangeDto> created = commands.createAll(request.changes(), user.username());
        for (int index = 0; index < created.size(); index++) {
            recommendations.after(shown.get(index), request.changes().get(index), created.get(index));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    ChangeDto update(@PathVariable UUID id, @Valid @RequestBody UpdateChangeRequest request, CurrentUser user) {
        return commands.update(id, request, user.username());
    }

    /** Cancels the change (status CANCELLED); an applied change cannot be cancelled (422). */
    @DeleteMapping("/{id}")
    ResponseEntity<Void> cancel(@PathVariable UUID id, CurrentUser user) {
        commands.cancel(id, user.username());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/request-confirmation")
    ChangeDto requestConfirmation(@PathVariable UUID id, CurrentUser user) {
        return commands.requestConfirmation(id, user.username());
    }

    /** Confirmation by the dispatcher instead of the teacher. */
    @PostMapping("/{id}/confirm")
    ChangeDto confirm(@PathVariable UUID id, CurrentUser user) {
        return commands.confirm(id, user.username());
    }
}
