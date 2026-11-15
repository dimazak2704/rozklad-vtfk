package ua.vtc.rozklad.core.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration tests share one database that holds the reference data of migrations V1_201, V1_203 and
 * V1_802 (bell profiles, shift windows, builtin period kinds, setting defaults). Test clean-ups truncate their own tables
 * and call {@link #restore} instead of truncating reference tables, so every test class sees the
 * same reference data whatever the run order.
 */
public final class ReferenceData {

    /** Names of the bell profiles seeded by V1_201; tests create profiles with other names. */
    public static final String DEFAULT_BELL_PROFILE = "2 зміни, 60 хв";

    private static final String RESTORE = """
            DELETE FROM bell_profile
            WHERE name NOT IN ('2 зміни, 60 хв', '2 зміни, 50 хв', '1 зміна, 80 хв')
              AND id NOT IN (SELECT bell_profile_id FROM semester WHERE bell_profile_id IS NOT NULL);
            DELETE FROM setting WHERE key <> 'calendar.activeBellProfileId';
            UPDATE setting
            SET value = (SELECT to_jsonb(id::text) FROM bell_profile WHERE name = '2 зміни, 60 хв')
            WHERE key = 'calendar.activeBellProfileId';
            DELETE FROM period_kind
            WHERE NOT builtin AND id NOT IN (SELECT kind_id FROM calendar_period);
            UPDATE period_kind SET active = true WHERE builtin AND NOT active;
            UPDATE period_kind k
            SET effect = seeded.effect
            FROM (VALUES ('К', 'DAY_OFF'), ('С', 'DAY_OFF'), ('БЗ', 'DAY_OFF'), ('НП', 'SUSPEND'), ('ВП', 'SUSPEND'),
                         ('ПП', 'SUSPEND'), ('Т', 'SUSPEND'), ('ЗУ', 'SUSPEND'), ('НС', 'SUSPEND'), ('І', 'SUSPEND'),
                         ('Д', 'SUSPEND'), ('А', 'SUSPEND'), ('ФН', 'ONLINE'), ('Р', 'INFO'),
                         ('ВКР', 'INFO')) AS seeded (code, effect)
            WHERE k.builtin AND k.code = seeded.code AND k.effect <> seeded.effect;
            """;

    private ReferenceData() {
    }

    /**
     * Removes test bell profiles, period kinds tests added and setting overrides (builtin kinds a test switched
     * off are switched on again and get their seeded effect back); settings without a row fall back to
     * {@code SystemSettings.DEFAULTS}, which equal the V1_802/V1_805 values. Call after the semesters of
     * the test are deleted.
     */
    public static void restore(JdbcTemplate jdbc) {
        jdbc.execute(RESTORE);
    }
}
