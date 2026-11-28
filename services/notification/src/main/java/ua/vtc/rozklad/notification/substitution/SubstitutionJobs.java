package ua.vtc.rozklad.notification.substitution;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.notification.binding.ChatBinding;
import ua.vtc.rozklad.notification.config.BotProperties;
import ua.vtc.rozklad.notification.delivery.DeliveryPurpose;
import ua.vtc.rozklad.notification.delivery.OutboundDraft;
import ua.vtc.rozklad.notification.delivery.Recipient;
import ua.vtc.rozklad.notification.email.EmailKind;
import ua.vtc.rozklad.notification.text.WebLinks;

/**
 * The periodic jobs on substitutions: the reminder after a passed deadline (CONFIRM only) with a notice to
 * admins, the calm warnings about teachers without Telegram (both write the e-mail intents too,
 * {@link SubstitutionMails}, and with the bot off only those), and the purge of old local copies.
 */
@Service
public class SubstitutionJobs {

    private static final Logger log = LoggerFactory.getLogger(SubstitutionJobs.class);
    private static final Comparator<SubstitutionRequest> BY_TEACHER_DATE_PAIR =
            Comparator.comparing(SubstitutionRequest::getTeacherId)
                    .thenComparing(SubstitutionRequest::getLessonDate)
                    .thenComparingInt(SubstitutionRequest::getPair);

    private final SubstitutionRequestRepository requests;
    private final SubstitutionChats chats;
    private final SubstitutionMails mails;
    private final TeacherNames names;
    private final WebLinks links;
    private final TransactionTemplate transactions;
    private final BotProperties properties;
    private final Clock clock;

    /** Public for tests of the scheduler, which stub the jobs. */
    public SubstitutionJobs(SubstitutionRequestRepository requests, SubstitutionChats chats,
                                 SubstitutionMails mails, TeacherNames names, WebLinks links,
                                 TransactionTemplate transactions, BotProperties properties, Clock clock) {
        this.requests = requests;
        this.chats = chats;
        this.mails = mails;
        this.names = names;
        this.links = links;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    /** The reminder of one deadline round: core's re-sent request and {@link #remindOverdue()} share it. */
    static String reminderKey(SubstitutionRequest request) {
        return "reminder:" + request.getChangeId() + ":" + request.getDeadline().toEpochMilli();
    }

    /**
     * Deadline passed without an answer: one reminder to the teacher and a notice to admins. Requests
     * that never reached the teacher (no Telegram) were already reported by
     * {@link #reportTeachersWithoutTelegram()}; lessons already over (an earlier day, or today after the pair's
     * end) are not worth a reminder or a notice. Teacher names are
     * looked up before the rows are locked, so no call to core holds the locks.
     */
    public int remindOverdue() {
        return remindOverdue(true);
    }

    /**
     * @param telegram the bot is on: with it off only the e-mail intents are written — a Telegram row would wait
     *                 in the queue for nobody and could go out a day late if the bot came back
     */
    public int remindOverdue(boolean telegram) {
        Instant now = clock.instant();
        Map<UUID, String> teacherNames = names.of(requests.findOverdueTeachers(now));
        if (teacherNames.isEmpty()) {
            return 0;
        }
        return count(transactions.execute(tx -> remindOverdue(now, teacherNames, telegram)));
    }

    private int remindOverdue(Instant now, Map<UUID, String> teacherNames, boolean telegram) {
        List<SubstitutionRequest> overdue = requests.findOverdue(now);
        for (SubstitutionRequest request : overdue) {
            request.markReminded(now);
            String key = request.getChangeId() + ":" + request.getDeadline().toEpochMilli();
            boolean lessonOver = LessonTime.isOver(clock, request.getLessonDate(), request.details().endsAt());
            if (!lessonOver) {
                // e-mail also reaches a teacher without Telegram (his request may have come by e-mail)
                mails.toTeacher(EmailKind.SUBSTITUTION_REMINDER, request, request.getTeacherId(), null, false, null,
                        reminderKey(request) + ":mail");
                mails.toAdmins(EmailKind.REQUEST_OVERDUE, names.name(teacherNames, request.getTeacherId()),
                        request.getChangeId(), request, null, null, null, request.getDeadline(), null,
                        "overdue:" + key + ":mail");
            }
            if (!telegram || request.isWithoutTelegram() || lessonOver) {
                continue;
            }
            chats.teacherChat(request.getTeacherId()).ifPresent(chat -> chats.enqueue(
                    OutboundDraft.text(Recipient.of(chat), DeliveryPurpose.SUBSTITUTION_REMINDER,
                                    SubstitutionTexts.request(request, true))
                            .withKeyboard(SubstitutionTexts.buttons(request.getChangeId()))
                            .correlatedWith(request.getChangeId())
                            .dedupBy(reminderKey(request))));
            String text = SubstitutionTexts.adminOverdue(names.name(teacherNames, request.getTeacherId()), request,
                    links.adminChanges(request.getLessonDate()));
            for (ChatBinding admin : chats.adminChats()) {
                chats.enqueue(OutboundDraft.text(Recipient.of(admin), DeliveryPurpose.SUBSTITUTION_ADMIN, text)
                        .correlatedWith(request.getChangeId())
                        .dedupBy("overdue:" + key + ":" + admin.getChatId()));
            }
        }
        if (!overdue.isEmpty()) {
            log.info("{} substitution request(s) passed the deadline without an answer", overdue.size());
        }
        return overdue.size();
    }

    /**
     * Calm warnings about teachers without Telegram: only requests still pending after
     * {@code app.bot.no-telegram-notice-delay} (a seeded or quickly confirmed/published order causes
     * none), only for today and later, and at most one message per teacher and lesson date for each
     * admin chat — the same teacher's further changes of that date are in the web panel.
     *
     * @return number of (teacher, date) warnings queued
     */
    public int reportTeachersWithoutTelegram() {
        return reportTeachersWithoutTelegram(true);
    }

    /** @param telegram the bot is on; with it off only the dispatchers' e-mail intents are written */
    public int reportTeachersWithoutTelegram(boolean telegram) {
        Instant noticedBefore = clock.instant().minus(properties.noTelegramNoticeDelay());
        Map<UUID, String> teacherNames = names.of(requests.findUnreportedWithoutTelegramTeachers(noticedBefore));
        if (teacherNames.isEmpty()) {
            return 0;
        }
        return count(transactions.execute(tx -> reportTeachersWithoutTelegram(noticedBefore, teacherNames,
                telegram)));
    }

    private int reportTeachersWithoutTelegram(Instant noticedBefore, Map<UUID, String> teacherNames,
                                              boolean telegram) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock);
        // rows are locked in change id order (as in remindOverdue), grouped by teacher, date and pair
        List<SubstitutionRequest> due = new ArrayList<>(requests.findUnreportedWithoutTelegram(noticedBefore));
        due.sort(BY_TEACHER_DATE_PAIR);
        Map<TeacherDate, List<SubstitutionRequest>> byTeacherAndDate = new LinkedHashMap<>();
        for (SubstitutionRequest request : due) {
            request.markAdminWarned(now);
            if (!request.getLessonDate().isBefore(today)) {
                byTeacherAndDate.computeIfAbsent(new TeacherDate(request.getTeacherId(), request.getLessonDate(),
                        request.isNotice()), key -> new ArrayList<>()).add(request);
            }
        }
        if (byTeacherAndDate.isEmpty()) {
            return 0;
        }
        List<ChatBinding> admins = telegram ? chats.adminChats() : List.of();
        int warnings = 0;
        for (Map.Entry<TeacherDate, List<SubstitutionRequest>> entry : byTeacherAndDate.entrySet()) {
            TeacherDate key = entry.getKey();
            String name = names.name(teacherNames, key.teacherId());
            String text = SubstitutionTexts.adminNoTelegram(name, entry.getValue(), links.adminChanges(key.date()));
            mails.teacherUnreachable(key.teacherId(), name, key.date(),
                    key.notice(), entry.getValue(), "no-telegram:" + key.teacherId() + ":" + key.date()
                            + (key.notice() ? ":notice" : "") + ":mail");
            boolean queued = false;
            for (ChatBinding admin : admins) {
                queued |= !chats.enqueue(OutboundDraft.text(Recipient.of(admin), DeliveryPurpose.SUBSTITUTION_ADMIN,
                                text)
                        .dedupBy("no-telegram:" + key.teacherId() + ":" + key.date() + (key.notice() ? ":notice:" : ":")
                                + admin.getChatId()))
                        .isEmpty();
            }
            if (queued) {
                warnings++;
            }
        }
        log.info("Teachers without Telegram: {} warning(s) for {} pending request(s)", warnings, due.size());
        return warnings;
    }

    @Transactional
    public int purgeLessonsBefore(LocalDate before) {
        return requests.deleteLessonsBefore(before);
    }

    private static int count(Integer value) {
        return value == null ? 0 : value;
    }

    private record TeacherDate(UUID teacherId, LocalDate date, boolean notice) {
    }
}
