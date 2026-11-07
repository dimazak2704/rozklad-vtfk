package ua.vtc.rozklad.core.substitution.internal.change;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.ConfirmationDecision;
import ua.vtc.rozklad.core.shared.security.CurrentUser;
import ua.vtc.rozklad.core.substitution.internal.support.CurrentTeacher;

/** «Мої заміни» in the teacher's cabinet (CONTRACTS §3). */
@RestController
@RequestMapping("/api/me/changes")
class MyChangeController {

    private final ChangeQueries queries;
    private final ChangeReplies replies;
    private final CurrentTeacher currentTeacher;

    MyChangeController(ChangeQueries queries, ChangeReplies replies, CurrentTeacher currentTeacher) {
        this.queries = queries;
        this.replies = replies;
        this.currentTeacher = currentTeacher;
    }

    /** Changes where I am the new teacher. */
    @GetMapping
    List<ChangeDto> mine(@RequestParam(required = false) ChangeStatus status, CurrentUser user) {
        return queries.assignedTo(currentTeacher.require(user).getId(), status);
    }

    @PostMapping("/{id}/confirm")
    ChangeDto confirm(@PathVariable UUID id, @Valid @RequestBody(required = false) ReplyRequests.Comment body,
                      CurrentUser user) {
        return replies.replyAsUser(id, user, ConfirmationDecision.CONFIRM, body == null ? null : body.comment());
    }

    @PostMapping("/{id}/decline")
    ChangeDto decline(@PathVariable UUID id, @Valid @RequestBody(required = false) ReplyRequests.Comment body,
                      CurrentUser user) {
        return replies.replyAsUser(id, user, ConfirmationDecision.DECLINE, body == null ? null : body.comment());
    }
}
