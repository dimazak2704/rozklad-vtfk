package ua.vtc.rozklad.notification.documents;

import java.time.Clock;
import java.time.LocalDate;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.core.CoreQueries;
import ua.vtc.rozklad.notification.text.UaDates;
import ua.vtc.rozklad.notification.web.HistoryNotPublicException;

/**
 * Core's public history window for order documents (owner decision 14.11.2026): a visitor opens orders dated
 * from {@code publicHistoryFrom} of core's meta on (cached 30 s); staff open every published order. Orders of
 * today and later need no meta. Core unreachable with nothing cached: {@code UpstreamUnavailableException} (503),
 * never a silent "visible".
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
        if (!orderDate.isBefore(LocalDate.now(clock))) {
            return;
        }
        LocalDate from = core.meta().publicHistoryFrom();
        if (from != null && orderDate.isBefore(from)) {
            throw new HistoryNotPublicException(from, "Розпорядження за дні до %s бачить лише персонал коледжу."
                    .formatted(UaDates.fullDate(from)));
        }
    }
}
