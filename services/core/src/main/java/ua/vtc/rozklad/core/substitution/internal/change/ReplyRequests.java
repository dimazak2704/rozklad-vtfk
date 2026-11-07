package ua.vtc.rozklad.core.substitution.internal.change;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import ua.vtc.rozklad.contracts.ConfirmationDecision;

/** Request bodies of the teacher's answer to a substitution request. */
public final class ReplyRequests {

    private ReplyRequests() {
    }

    /** {@code POST /api/me/changes/{id}/confirm|decline}. */
    public record Comment(@Size(max = 500) String comment) {
    }

    /** {@code POST /api/internal/changes/{id}/reply} from the Telegram bot. */
    public record BotReply(@NotNull UUID teacherId, @NotNull ConfirmationDecision decision,
                           @Size(max = 500) String comment) {
    }
}
