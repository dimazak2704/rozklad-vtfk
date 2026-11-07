package ua.vtc.rozklad.notification.substitution;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SubstitutionRequestRepository extends JpaRepository<SubstitutionRequest, UUID> {

    @Query("""
            select r from SubstitutionRequest r
            where r.status = ua.vtc.rozklad.contracts.ChangeStatus.PENDING_CONFIRMATION
              and r.remindedAt is null and r.deadline is not null and r.deadline < :now
            order by r.deadline
            """)
    List<SubstitutionRequest> findOverdue(Instant now);

    @Modifying
    @Query("delete from SubstitutionRequest r where r.lessonDate < :before")
    int deleteLessonsBefore(LocalDate before);
}
