package ua.vtc.rozklad.notification.binding;

import java.util.Objects;
import java.util.UUID;

/**
 * What a code binds a chat to; {@code label} is the human name (group code, teacher name).
 * Invariants match the DB checks: groups for GROUP_CHAT/SUBSCRIBER, a teacher for TEACHER.
 */
public record BindingTarget(BindingType type, UUID groupId, String groupCode, UUID teacherId, String label) {

    public BindingTarget {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(label, "label");
        if (type.needsGroup() && (groupId == null || groupCode == null)) {
            throw new IllegalArgumentException(type + " needs a group");
        }
        if (type == BindingType.TEACHER && teacherId == null) {
            throw new IllegalArgumentException("TEACHER needs a teacher");
        }
    }

    public static BindingTarget group(BindingType type, UUID groupId, String groupCode) {
        return new BindingTarget(type, groupId, groupCode, null, groupCode);
    }

    public static BindingTarget teacher(UUID teacherId, String name) {
        return new BindingTarget(BindingType.TEACHER, null, null, teacherId, name);
    }

    public static BindingTarget adminChat() {
        return new BindingTarget(BindingType.ADMIN_CHAT, null, null, null, "Службовий чат");
    }
}
