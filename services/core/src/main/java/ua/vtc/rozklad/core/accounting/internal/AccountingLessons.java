package ua.vtc.rozklad.core.accounting.internal;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.timetable.LessonStatus;

/** Reads the SCHEDULED lessons of a semester as light projections (one query per request). */
@Component
class AccountingLessons {

    private static final String SELECT = """
            select new ua.vtc.rozklad.core.accounting.internal.ScheduledLessonFact(
                l.id, l.groupId, l.disciplineId, l.subgroupNumber, l.date, l.pairNumber)
            from Lesson l
            where l.semesterId = :semesterId and l.status = :status
            """;

    private final EntityManager entityManager;

    AccountingLessons(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    List<ScheduledLessonFact> scheduled(UUID semesterId, UUID groupId) {
        String jpql = groupId == null ? SELECT : SELECT + " and l.groupId = :groupId";
        var query = entityManager.createQuery(jpql, ScheduledLessonFact.class)
                .setParameter("semesterId", semesterId)
                .setParameter("status", LessonStatus.SCHEDULED);
        if (groupId != null) {
            query.setParameter("groupId", groupId);
        }
        return query.getResultList();
    }
}
