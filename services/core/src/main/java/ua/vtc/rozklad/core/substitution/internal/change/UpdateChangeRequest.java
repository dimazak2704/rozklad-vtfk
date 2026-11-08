package ua.vtc.rozklad.core.substitution.internal.change;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import ua.vtc.rozklad.core.shared.domain.ReasonCode;

/**
 * {@code PUT /api/admin/changes/{id}}: the room and the printed reason of an open change. Another
 * teacher or discipline means another decision: cancel the change and create a new one, so the
 * teacher who was asked gets a clear «скасовано». {@code version} (from {@code ChangeDto}) is
 * required: a stale one gets 409 {@code stale-data}, so concurrent edits are never overwritten
 * silently; {@code newRoomId == null} keeps the original room.
 */
public record UpdateChangeRequest(
        UUID newRoomId,
        @NotNull ReasonCode reasonCode,
        @Size(max = 100) String reasonText,
        @NotNull(message = "Вкажіть версію зміни (поле version з відповіді)") Long version) {
}
