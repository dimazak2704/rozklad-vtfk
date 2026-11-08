package ua.vtc.rozklad.notification.substitution;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SubstitutionRequestRepository extends JpaRepository<SubstitutionRequest, UUID> {

    /**
     * The request with its row locked until the transaction ends. Every writer (the teacher's button,
     * core's events, the scheduled jobs) reads through a locking query, so two of them never update
     * the same version: the second one waits for the first one's commit and then sees its result.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SubstitutionRequest r where r.changeId = :changeId")
    Optional<SubstitutionRequest> findForUpdate(UUID changeId);

    /** Pending requests whose deadline passed and that were not reminded yet. */
    String OVERDUE = """
            r.status = ua.vtc.rozklad.contracts.ChangeStatus.PENDING_CONFIRMATION
              and r.remindedAt is null and r.deadline is not null and r.deadline < :now
            """;

    /**
     * Messages that never reached a teacher without Telegram, older than {@code noticedBefore}, not reported to
     * admins yet, and still worth a word: a pending request (CONFIRM) or an unanswered notice of a change in
     * force (NOTIFY: CONFIRMED or already APPLIED).
     */
    String UNREPORTED_WITHOUT_TELEGRAM = """
            r.noTelegramAt is not null and r.noTelegramAt <= :noticedBefore and r.adminWarnedAt is null
              and (r.status = ua.vtc.rozklad.contracts.ChangeStatus.PENDING_CONFIRMATION
                   or (r.mode = ua.vtc.rozklad.contracts.SubstitutionRequestMode.NOTIFY and r.teacherAnswer is null
                       and r.status in (ua.vtc.rozklad.contracts.ChangeStatus.CONFIRMED,
                                        ua.vtc.rozklad.contracts.ChangeStatus.APPLIED)))
            """;

    /*
     * The locking queries of the scheduled jobs lock their rows in one order, by change id. A request of a
     * teacher without Telegram can be overdue and unreported at once, so both jobs may lock it; with
     * different orders (deadline vs. teacher/date/pair) two of them — or two instances — could deadlock.
     */

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SubstitutionRequest r where " + OVERDUE + " order by r.changeId")
    List<SubstitutionRequest> findOverdue(Instant now);

    /** Teachers of {@link #findOverdue}, read without locks (their names are looked up before locking). */
    @Query("select distinct r.teacherId from SubstitutionRequest r where " + OVERDUE)
    List<UUID> findOverdueTeachers(Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SubstitutionRequest r where " + UNREPORTED_WITHOUT_TELEGRAM + " order by r.changeId")
    List<SubstitutionRequest> findUnreportedWithoutTelegram(Instant noticedBefore);

    /** Teachers of {@link #findUnreportedWithoutTelegram}, read without locks. */
    @Query("select distinct r.teacherId from SubstitutionRequest r where " + UNREPORTED_WITHOUT_TELEGRAM)
    List<UUID> findUnreportedWithoutTelegramTeachers(Instant noticedBefore);

    @Modifying
    @Query("delete from SubstitutionRequest r where r.lessonDate < :before")
    int deleteLessonsBefore(LocalDate before);
}
