package ua.vtc.rozklad.notification.email;

import java.time.Clock;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.notification.config.StandClock;

/** Per-account e-mail settings: only deviations from {@link EmailKind}'s defaults are stored. */
@Service
public class EmailPreferences {

    private final EmailPreferenceRepository repository;
    private final Clock clock;

    public EmailPreferences(EmailPreferenceRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = StandClock.real(clock);
    }

    /** The account's settings, the defaults when it never saved any. */
    @Transactional(readOnly = true)
    public EmailPreference of(UUID accountId) {
        return repository.findById(accountId).orElseGet(() -> EmailPreference.defaults(accountId));
    }

    @Transactional(readOnly = true)
    public Map<UUID, EmailPreference> of(Collection<UUID> accountIds) {
        Map<UUID, EmailPreference> stored = repository.findByAccountIdIn(accountIds).stream()
                .collect(Collectors.toMap(EmailPreference::getAccountId, Function.identity()));
        return accountIds.stream().distinct().collect(Collectors.toMap(Function.identity(),
                id -> stored.getOrDefault(id, EmailPreference.defaults(id))));
    }

    /**
     * Whether anybody may want letters of {@code kind}: always for kinds that are on by default; an off-by-default
     * kind only once somebody switched it on (so most absences write no intent at all).
     */
    @Transactional(readOnly = true)
    public boolean mightWant(EmailKind kind) {
        return kind.defaultOn() || !kind.switchable() || repository.anyoneEnabled(kind.name());
    }

    /**
     * Saves the master switch and the given kinds (the others keep their state).
     *
     * @param expectedVersion the version the client read, or null to skip the check
     * @throws OptimisticLockingFailureException when somebody saved in between
     */
    @Transactional
    public EmailPreference update(UUID accountId, boolean enabled, Map<EmailKind, Boolean> kinds,
                                  Long expectedVersion) {
        EmailPreference preference = repository.findById(accountId)
                .orElseGet(() -> new EmailPreference(accountId, clock.instant()));
        long current = preference.isNew() ? 0 : preference.getVersion();
        if (expectedVersion != null && expectedVersion != current) {
            throw new OptimisticLockingFailureException("E-mail settings of " + accountId + " changed");
        }
        preference.update(enabled, kinds, clock.instant());
        return repository.saveAndFlush(preference);
    }
}
