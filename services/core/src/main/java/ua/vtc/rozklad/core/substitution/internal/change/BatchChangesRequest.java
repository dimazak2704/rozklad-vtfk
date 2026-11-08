package ua.vtc.rozklad.core.substitution.internal.change;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** {@code POST /api/admin/changes/batch}: changes created in this order in one transaction. */
public record BatchChangesRequest(@NotEmpty @Size(max = 50) List<@Valid CreateChangeRequest> changes) {
}
