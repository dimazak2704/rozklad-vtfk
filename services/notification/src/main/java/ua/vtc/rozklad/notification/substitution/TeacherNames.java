package ua.vtc.rozklad.notification.substitution;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.binding.ChatBinding;
import ua.vtc.rozklad.notification.binding.ChatBindings;
import ua.vtc.rozklad.notification.core.CoreDtos.PublicTeacher;
import ua.vtc.rozklad.notification.core.CoreQueries;
import ua.vtc.rozklad.notification.core.UpstreamRejectedException;
import ua.vtc.rozklad.notification.core.UpstreamUnavailableException;

/**
 * Teacher names for admin texts. A lookup can be an HTTP call to core, so callers look names up before they lock
 * rows; {@link #name(Map, UUID)} covers a teacher who appeared in between.
 */
@Component
class TeacherNames {

    static final String UNKNOWN_TEACHER = "Викладач";
    private static final Logger log = LoggerFactory.getLogger(TeacherNames.class);

    private final CoreQueries queries;
    private final ChatBindings bindings;

    TeacherNames(CoreQueries queries, ChatBindings bindings) {
        this.queries = queries;
        this.bindings = bindings;
    }

    /** Display name for admin texts; core down ⇒ the name saved with the teacher's binding. */
    String of(UUID teacherId) {
        if (teacherId == null) {
            return UNKNOWN_TEACHER;
        }
        try {
            Optional<PublicTeacher> teacher = queries.teacher(teacherId);
            if (teacher.isPresent() && !teacher.get().label().isBlank()) {
                return teacher.get().label();
            }
        } catch (UpstreamUnavailableException | UpstreamRejectedException e) {
            log.debug("Teacher name from core unavailable: {}", e.getMessage());
        }
        return bindings.teacherChat(teacherId).map(ChatBinding::getTitle).orElse(UNKNOWN_TEACHER);
    }

    /** Names of the teachers, in their order. */
    Map<UUID, String> of(List<UUID> teacherIds) {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (UUID teacherId : teacherIds) {
            names.put(teacherId, of(teacherId));
        }
        return names;
    }

    /** A name looked up before locking; a teacher who appeared in between is looked up now. */
    String name(Map<UUID, String> names, UUID teacherId) {
        String name = names.get(teacherId);
        return name != null ? name : of(teacherId);
    }
}
