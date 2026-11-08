package ua.vtc.rozklad.core.shared.web;

import java.util.Objects;
import java.util.UUID;

/** {@code Ref {id, label}} of CONTRACTS.md: a link to another record with a short display text. */
public record Ref(UUID id, String label) {

    public Ref {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
    }
}
