package ua.vtc.rozklad.core.settings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SettingRepository extends JpaRepository<Setting, String> {

    /** Serializes writes of the settings (transaction-scoped advisory lock), so a revision check holds. */
    @Query(value = "select 1 from pg_advisory_xact_lock(hashtextextended('settings', 0))", nativeQuery = true)
    Integer lockForUpdate();
}
