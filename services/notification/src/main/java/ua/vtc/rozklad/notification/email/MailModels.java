package ua.vtc.rozklad.notification.email;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * What a letter says, snapshot at the moment of the event and stored as JSON with the intent
 * ({@code email_notice.model}). Only data: wording is {@link MailTexts}, layout the templates. Each
 * {@link EmailKind} names its model type.
 */
public final class MailModels {

    private MailModels() {
    }

    public sealed interface MailModel permits SubstitutionMail, GenerationMail, AdminSubstitutionMail, AbsenceMail,
            UnreachableMail, OrderMail, RelinkedMail, TestMail {
    }

    /**
     * A teacher's substitution as he reads it (the Telegram details). {@code mode}: {@code NOTIFY} or
     * {@code CONFIRM}; {@code whatChanged}: the line of a changed letter («Нова аудиторія: 214 (була 6)»);
     * {@code confirmed}: a CONFIRM request already confirmed (an update only informs); {@code reassigned}: the
     * change went to another teacher (the letter to the previous one).
     */
    public record SubstitutionMail(UUID changeId, String mode, LocalDate date, int pair, LocalTime startsAt,
                                   LocalTime endsAt, String groupCode, int subgroup, String disciplineName,
                                   String changeKind, String originalDisciplineName, String originalTeacherName,
                                   String reasonCode, String roomName, Instant deadline, String whatChanged,
                                   boolean confirmed, boolean reassigned) implements MailModel {

        public boolean notice() {
            return "NOTIFY".equals(mode);
        }

        public SubstitutionMail asReassigned() {
            return new SubstitutionMail(changeId, mode, date, pair, startsAt, endsAt, groupCode, subgroup,
                    disciplineName, changeKind, originalDisciplineName, originalTeacherName, reasonCode, roomName,
                    deadline, null, confirmed, true);
        }
    }

    /** {@code status}: SOLVED, FAILED or CANCELLED; scores as absolute values. */
    public record GenerationMail(UUID jobId, String status, long hardScore, long softScore, List<Penalty> penalties,
                                 String error) implements MailModel {

        public GenerationMail {
            penalties = penalties == null ? List.of() : List.copyOf(penalties);
        }
    }

    public record Penalty(String constraint, int count) {
    }

    /** A refusal, an objection or an unanswered request, for the dispatchers. */
    public record AdminSubstitutionMail(UUID changeId, String teacherName, LocalDate date, Integer pair,
                                        String groupCode, String disciplineName, Instant deadline)
            implements MailModel {
    }

    /** Never the reason: it is private (it is not even in the event). {@code pairs}: «увесь день», «пари 1, 2». */
    public record AbsenceMail(UUID absenceId, String teacherName, LocalDate dateFrom, LocalDate dateTo, String pairs,
                              String source) implements MailModel {
    }

    /** {@code lessons}: «2 пара · 4КІ1 · Дисципліна» of one date. */
    public record UnreachableMail(UUID teacherId, String teacherName, LocalDate date, boolean notice,
                                  List<String> lessons) implements MailModel {

        public UnreachableMail {
            lessons = lessons == null ? List.of() : List.copyOf(lessons);
        }
    }

    /** {@code rows}: the teacher's rows of the order, one plain line each. */
    public record OrderMail(UUID orderId, Integer number, boolean amendment, LocalDate date, String parityLabel,
                            List<String> rows) implements MailModel {

        public OrderMail {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    /** {@code telegramAccount}: «Ім’я (@nick)» of who redeemed the newer code, when known. */
    public record RelinkedMail(String teacherName, String telegramAccount, Instant at) implements MailModel {
    }

    public record TestMail(Instant requestedAt) implements MailModel {
    }
}
