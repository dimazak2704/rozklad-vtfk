package ua.vtc.rozklad.core.shared.ref;

import java.util.UUID;

/** {@code GroupRef} of CONTRACTS.md: a group as other DTOs reference it. */
public record GroupRef(UUID id, String code, int course) {
}
