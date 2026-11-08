package ua.vtc.rozklad.core.substitution.internal.change;

import static ua.vtc.rozklad.contracts.ChangeStatus.APPLIED;
import static ua.vtc.rozklad.contracts.ChangeStatus.CANCELLED;
import static ua.vtc.rozklad.contracts.ChangeStatus.CONFIRMED;
import static ua.vtc.rozklad.contracts.ChangeStatus.DECLINED;
import static ua.vtc.rozklad.contracts.ChangeStatus.DRAFT;
import static ua.vtc.rozklad.contracts.ChangeStatus.PENDING_CONFIRMATION;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;

/**
 * Allowed status transitions of a change (DOMAIN §7):
 * <pre>
 * DRAFT ──► PENDING_CONFIRMATION ──► CONFIRMED ──► APPLIED
 *   │               │    ▲                │
 *   └──► CONFIRMED  └──► DECLINED ─┘ (new request)
 * any open status ──► CANCELLED
 * </pre>
 * APPLIED and CANCELLED are final. A declined change returns its lesson to «needs decision»; it
 * can only be asked again or cancelled.
 *
 * <p>One transition is not in the table because only one actor may take it: in approval mode NOTIFY
 * the informed teacher answers «Не можу» to a change that is CONFIRMED but not yet published, and it
 * becomes DECLINED ({@link #requireTeacherDecline}). The dispatcher cannot decline a confirmed change.
 */
public final class ChangeStatusMachine {

    public static final String ILLEGAL_TRANSITION = "CHANGE_ILLEGAL_TRANSITION";

    private static final Map<ChangeStatus, Set<ChangeStatus>> ALLOWED = new EnumMap<>(ChangeStatus.class);

    static {
        ALLOWED.put(DRAFT, EnumSet.of(PENDING_CONFIRMATION, CONFIRMED, CANCELLED));
        ALLOWED.put(PENDING_CONFIRMATION, EnumSet.of(CONFIRMED, DECLINED, CANCELLED));
        ALLOWED.put(CONFIRMED, EnumSet.of(APPLIED, CANCELLED));
        ALLOWED.put(DECLINED, EnumSet.of(PENDING_CONFIRMATION, CANCELLED));
        ALLOWED.put(CANCELLED, EnumSet.noneOf(ChangeStatus.class));
        ALLOWED.put(APPLIED, EnumSet.noneOf(ChangeStatus.class));
    }

    private ChangeStatusMachine() {
    }

    public static boolean canTransition(ChangeStatus from, ChangeStatus to) {
        return ALLOWED.get(from).contains(to);
    }

    /** @throws BusinessRuleException (422, {@value #ILLEGAL_TRANSITION}) for a transition DOMAIN §7 does not allow */
    public static void requireTransition(ChangeStatus from, ChangeStatus to) {
        if (!canTransition(from, to)) {
            throw new BusinessRuleException(ILLEGAL_TRANSITION,
                    "Зміну зі статусом «%s» не можна перевести у статус «%s».".formatted(label(from), label(to)));
        }
    }

    /**
     * CONFIRMED → DECLINED when the informed teacher of a NOTIFY change cannot take it (DOMAIN §7).
     *
     * @throws BusinessRuleException (422, {@value #ILLEGAL_TRANSITION}) from any other status
     */
    public static void requireTeacherDecline(ChangeStatus from) {
        if (from != CONFIRMED) {
            throw new BusinessRuleException(ILLEGAL_TRANSITION,
                    "Зміну зі статусом «%s» не можна перевести у статус «%s».".formatted(label(from), label(DECLINED)));
        }
    }

    public static boolean isFinal(ChangeStatus status) {
        return ALLOWED.get(status).isEmpty();
    }

    /** Ukrainian name of a status for messages. */
    public static String label(ChangeStatus status) {
        return switch (status) {
            case DRAFT -> "Чернетка";
            case PENDING_CONFIRMATION -> "Очікує підтвердження";
            case CONFIRMED -> "Підтверджено";
            case DECLINED -> "Відхилено";
            case CANCELLED -> "Скасовано";
            case APPLIED -> "Застосовано";
        };
    }
}
