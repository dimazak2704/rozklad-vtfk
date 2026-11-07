package ua.vtc.rozklad.core.substitution;

import java.util.Collection;
import java.util.UUID;

/**
 * Applies confirmed changes to the dated lessons when their order is published (DOMAIN §7):
 * original lessons are cancelled, new lessons (origin CHANGE / ADDITIONAL / BLOCK) are created,
 * slot conflicts are checked again, and the changes become APPLIED with the order id. Must run
 * inside the publication transaction: a conflict throws
 * {@link ua.vtc.rozklad.core.shared.error.ConflictException} with {@code conflicts[]} and
 * nothing is applied.
 */
public interface ChangePublication {

    AppliedChanges apply(Collection<UUID> changeIds, UUID orderId, String actor);
}
