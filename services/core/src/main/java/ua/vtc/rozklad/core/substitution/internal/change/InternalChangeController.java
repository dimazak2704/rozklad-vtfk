package ua.vtc.rozklad.core.substitution.internal.change;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Teacher's answer relayed by the Telegram bot (CONTRACTS §4, role SERVICE). */
@RestController
@RequestMapping("/api/internal/changes")
class InternalChangeController {

    private final ChangeReplies replies;

    InternalChangeController(ChangeReplies replies) {
        this.replies = replies;
    }

    /** 403 when {@code teacherId} is not the assigned teacher, 409 when the change no longer waits. */
    @PostMapping("/{id}/reply")
    ChangeDto reply(@PathVariable UUID id, @Valid @RequestBody ReplyRequests.BotReply body) {
        return replies.replyFromBot(id, body.teacherId(), body.decision(), body.comment());
    }
}
