package ua.vtc.rozklad.core.substitution.internal.options;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Declined requests from the change history (H6 of DOMAIN §7): a teacher who declined a lesson is
 * not offered it again; one who declined another lesson that day costs a little more. One query.
 */
@Component
class OptionsDeclines {

    private static final String SELECT = """
            select c.originalLessonId, c.newTeacherId, h.at
            from ChangeHistory h, Change c
            where h.changeId = c.id
              and h.toStatus = ua.vtc.rozklad.contracts.ChangeStatus.DECLINED
              and c.newTeacherId is not null
            """;

    private final EntityManager entityManager;

    OptionsDeclines(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** A decline: the lesson of the request (may be {@code null}), the teacher, when. */
    record Decline(UUID lessonId, UUID teacherId, Instant at) {
    }

    List<Decline> onDate(LocalDate date) {
        return entityManager.createQuery(SELECT + " and c.date = :date order by h.at", Object[].class)
                .setParameter("date", date)
                .getResultStream()
                .map(OptionsDeclines::decline)
                .toList();
    }

    List<Decline> forLesson(UUID lessonId) {
        return entityManager.createQuery(SELECT + " and c.originalLessonId = :lessonId order by h.at", Object[].class)
                .setParameter("lessonId", lessonId)
                .getResultStream()
                .map(OptionsDeclines::decline)
                .toList();
    }

    private static Decline decline(Object[] row) {
        return new Decline((UUID) row[0], (UUID) row[1], (Instant) row[2]);
    }
}
