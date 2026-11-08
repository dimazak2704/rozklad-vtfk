package ua.vtc.rozklad.core.settings.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import ua.vtc.rozklad.core.settings.ChangeApprovalMode;
import ua.vtc.rozklad.core.settings.Setting;
import ua.vtc.rozklad.core.settings.SettingRepository;
import ua.vtc.rozklad.core.settings.SettingsConstraint;
import ua.vtc.rozklad.core.settings.SettingsService;
import ua.vtc.rozklad.core.settings.SystemSettings;
import ua.vtc.rozklad.core.shared.error.FieldErrors;

/**
 * Settings stored as JSON values under {@link SettingKey}s. A missing row means "default"; a row
 * whose value has the wrong type is a data error and fails loudly instead of being replaced by
 * the default.
 */
@Service
class PersistentSettingsService implements SettingsService {

    /** Revision row of the settings (not a setting itself, ignored by {@link #current()}). */
    static final String VERSION_KEY = "settings.version";

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final SettingRepository settings;
    private final List<SettingsConstraint> constraints;

    PersistentSettingsService(SettingRepository settings, List<SettingsConstraint> constraints) {
        this.settings = settings;
        this.constraints = List.copyOf(constraints);
    }

    @Override
    @Transactional(readOnly = true)
    public SystemSettings current() {
        Map<String, JsonNode> stored = new HashMap<>();
        // JSON null (наприклад, типовий профіль дзвінків знято) — це значення, а не відсутній рядок
        settings.findAll().forEach(setting -> stored.put(setting.getKey(),
                setting.getValue() == null ? JSON.nullNode() : setting.getValue()));
        SystemSettings defaults = SystemSettings.DEFAULTS;
        return new SystemSettings(
                read(stored, SettingKey.APPROVAL_MODE, defaults.approvalMode(), PersistentSettingsService::approvalMode),
                read(stored, SettingKey.CONFIRM_TIMEOUT_MINUTES, defaults.confirmTimeoutMinutes(),
                        PersistentSettingsService::integer),
                read(stored, SettingKey.SIGNATORY_POSITION, defaults.signatoryPosition(), PersistentSettingsService::text),
                read(stored, SettingKey.SIGNATORY_NAME, defaults.signatoryName(), PersistentSettingsService::text),
                read(stored, SettingKey.COLLEGE_NAME, defaults.collegeName(), PersistentSettingsService::text),
                read(stored, SettingKey.HOURS_PER_PAIR, defaults.hoursPerPair(), PersistentSettingsService::integer),
                read(stored, SettingKey.SHOW_TEACHER_FULL_NAMES, defaults.showTeacherFullNames(),
                        PersistentSettingsService::bool),
                read(stored, SettingKey.ACTIVE_BELL_PROFILE_ID, defaults.activeBellProfileId(),
                        PersistentSettingsService::uuidOrNull));
    }

    @Override
    @Transactional(readOnly = true)
    public long version() {
        return storedVersion();
    }

    @Override
    @Transactional
    public SystemSettings update(SystemSettings proposed, long expectedVersion, String actor) {
        settings.lockForUpdate();
        if (storedVersion() != expectedVersion) {
            throw new OptimisticLockingFailureException("Settings changed: expected version " + expectedVersion);
        }
        return write(proposed, actor);
    }

    @Override
    @Transactional
    public SystemSettings update(SystemSettings proposed, String actor) {
        settings.lockForUpdate();
        return write(proposed, actor);
    }

    private SystemSettings write(SystemSettings proposed, String actor) {
        SystemSettings normalized = new SystemSettings(proposed.approvalMode(), proposed.confirmTimeoutMinutes(),
                proposed.signatoryPosition().strip(), proposed.signatoryName().strip(), proposed.collegeName().strip(),
                proposed.hoursPerPair(), proposed.showTeacherFullNames(), proposed.activeBellProfileId());
        FieldErrors errors = new FieldErrors();
        SettingsRules.check(normalized, errors);
        constraints.forEach(constraint -> constraint.check(normalized, errors));
        errors.throwIfAny();

        write(SettingKey.APPROVAL_MODE, JSON.stringNode(normalized.approvalMode().name()), actor);
        write(SettingKey.CONFIRM_TIMEOUT_MINUTES, JSON.numberNode(normalized.confirmTimeoutMinutes()), actor);
        write(SettingKey.SIGNATORY_POSITION, JSON.stringNode(normalized.signatoryPosition()), actor);
        write(SettingKey.SIGNATORY_NAME, JSON.stringNode(normalized.signatoryName()), actor);
        write(SettingKey.COLLEGE_NAME, JSON.stringNode(normalized.collegeName()), actor);
        write(SettingKey.HOURS_PER_PAIR, JSON.numberNode(normalized.hoursPerPair()), actor);
        write(SettingKey.SHOW_TEACHER_FULL_NAMES, JSON.booleanNode(normalized.showTeacherFullNames()), actor);
        write(SettingKey.ACTIVE_BELL_PROFILE_ID, normalized.activeBellProfileId() == null
                ? JSON.nullNode()
                : JSON.stringNode(normalized.activeBellProfileId().toString()), actor);
        Setting version = settings.findById(VERSION_KEY).orElse(null);
        if (version == null) {
            settings.save(new Setting(VERSION_KEY, JSON.numberNode(1L), actor));
        } else {
            version.update(JSON.numberNode(version.getValue().asLong() + 1), actor);
        }
        settings.flush();
        return normalized;
    }

    /** No row yet = revision 0 (settings never saved through the API). */
    private long storedVersion() {
        return settings.findById(VERSION_KEY).map(setting -> setting.getValue().asLong()).orElse(0L);
    }

    private void write(SettingKey key, JsonNode value, String actor) {
        settings.findById(key.key()).ifPresentOrElse(
                setting -> {
                    if (!Objects.equals(setting.getValue(), value)) {
                        setting.update(value, actor);
                    }
                },
                () -> settings.save(new Setting(key.key(), value, actor)));
    }

    private static <T> T read(Map<String, JsonNode> stored, SettingKey key, T defaultValue,
                              Function<JsonNode, T> parser) {
        JsonNode value = stored.get(key.key());
        if (value == null) {
            return defaultValue;
        }
        try {
            return parser.apply(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Setting '" + key.key() + "' has an invalid stored value: " + value,
                    invalid);
        }
    }

    private static ChangeApprovalMode approvalMode(JsonNode value) {
        return ChangeApprovalMode.valueOf(text(value));
    }

    private static Integer integer(JsonNode value) {
        if (!value.isInt()) {
            throw new IllegalArgumentException("integer expected");
        }
        return value.intValue();
    }

    private static String text(JsonNode value) {
        if (!value.isString()) {
            throw new IllegalArgumentException("string expected");
        }
        return value.stringValue();
    }

    private static Boolean bool(JsonNode value) {
        if (!value.isBoolean()) {
            throw new IllegalArgumentException("boolean expected");
        }
        return value.asBoolean();
    }

    private static UUID uuidOrNull(JsonNode value) {
        return value.isNull() ? null : UUID.fromString(text(value));
    }
}
