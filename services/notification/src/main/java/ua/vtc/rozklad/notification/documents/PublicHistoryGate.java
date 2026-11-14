package ua.vtc.rozklad.notification.documents;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.core.CoreQueries;
import ua.vtc.rozklad.notification.text.UaDates;
import ua.vtc.rozklad.notification.web.HistoryNotPublicException;

/**
 * Core's public history window for orders (owner decision 14.11.2026): a visitor opens orders dated from
 * {@code publicHistoryFrom} of core's meta on (cached 30 s); staff open every published order. Bound group chats are
 * visitors too, so a past-dated order skips them ({@code OrderDistribution}). Orders of today and later need no meta.
 * Core unreachable with nothing cached: {@code UpstreamUnavailableException} (503), never a silent "visible".
 */
@Component
public class PublicHistoryGate {

    private final CoreQueries core;
    private final Clock clock;

    public PublicHistoryGate(CoreQueries core, Clock clock) {
        this.core = core;
        this.clock = clock;
    }

    /** @throws HistoryNotPublicException an order dated before the window */
    public void requireVisible(LocalDate orderDate) {
        Optional<LocalDate> closed = closedFrom(orderDate);
        if (closed.isPresent()) {
            LocalDate from = closed.get();
            throw new HistoryNotPublicException(from, "Розпорядження за дні до %s бачить лише персонал коледжу."
                    .formatted(UaDates.fullDate(from)));
        }
    }

    /** {@code publicHistoryFrom} when an order of {@code orderDate} is before it (visitors may not see it), else empty. */
    public Optional<LocalDate> closedFrom(LocalDate orderDate) {
        if (!orderDate.isBefore(LocalDate.now(clock))) {
            return Optional.empty();
        }
        LocalDate from = core.meta().publicHistoryFrom();
        return from != null && orderDate.isBefore(from) ? Optional.of(from) : Optional.empty();
    }
}
