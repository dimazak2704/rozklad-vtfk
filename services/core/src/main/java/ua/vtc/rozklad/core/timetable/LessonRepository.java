package ua.vtc.rozklad.core.timetable;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface LessonRepository extends JpaRepository<Lesson, UUID> {

    List<Lesson> findAllByGroupIdAndDateBetweenOrderByDateAscPairNumberAsc(UUID groupId, LocalDate from,
                                                                            LocalDate to);

    List<Lesson> findAllByTeacherIdAndDateBetweenOrderByDateAscPairNumberAsc(UUID teacherId, LocalDate from,
                                                                              LocalDate to);

    List<Lesson> findAllByRoomIdAndDateBetweenOrderByDateAscPairNumberAsc(UUID roomId, LocalDate from,
                                                                           LocalDate to);

    List<Lesson> findAllByDateAndStatus(LocalDate date, LessonStatus status);

    /** Whether the group's timetable uses the subgroup on any date (the Excel import stores no subgroup rows). */
    boolean existsByGroupIdAndSubgroupNumber(UUID groupId, int subgroupNumber);

    /**
     * Locks all lessons of a group on a date ({@code SELECT … FOR UPDATE}) so that concurrent
     * changes cannot create a whole-group / subgroup overlap (DOMAIN §3). Call inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Lesson l where l.groupId = :groupId and l.date = :date order by l.pairNumber")
    List<Lesson> lockGroupDay(UUID groupId, LocalDate date);

    /**
     * Teacher / room / cohort slot constraints are deferred to commit. Call this after the changes
     * of a transaction to check them now: a clash raises {@code DataIntegrityViolationException}
     * (SQLState 23P01) inside the service instead of at commit.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "SET CONSTRAINTS uq_lesson_teacher_slot, uq_lesson_room_slot, uq_lesson_cohort_slot IMMEDIATE",
            nativeQuery = true)
    void checkSlotConstraintsNow();
}
