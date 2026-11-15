package ua.vtc.rozklad.notification.email;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EmailPreferenceRepository extends JpaRepository<EmailPreference, UUID> {

    List<EmailPreference> findByAccountIdIn(Collection<UUID> accountIds);

    /** Somebody switched an off-by-default kind on (otherwise its intents are not even written). */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM email_preference WHERE enabled AND overrides ->> :kind = 'true')",
            nativeQuery = true)
    boolean anyoneEnabled(String kind);
}
