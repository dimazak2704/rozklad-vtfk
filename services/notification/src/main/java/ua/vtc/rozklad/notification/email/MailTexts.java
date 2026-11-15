package ua.vtc.rozklad.notification.email;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.config.InfrastructureConfig;
import ua.vtc.rozklad.notification.email.MailContent.Detail;
import ua.vtc.rozklad.notification.email.MailContent.Link;
import ua.vtc.rozklad.notification.email.MailModels.AbsenceMail;
import ua.vtc.rozklad.notification.email.MailModels.AdminSubstitutionMail;
import ua.vtc.rozklad.notification.email.MailModels.GenerationMail;
import ua.vtc.rozklad.notification.email.MailModels.MailModel;
import ua.vtc.rozklad.notification.email.MailModels.OrderMail;
import ua.vtc.rozklad.notification.email.MailModels.Penalty;
import ua.vtc.rozklad.notification.email.MailModels.RelinkedMail;
import ua.vtc.rozklad.notification.email.MailModels.SubstitutionMail;
import ua.vtc.rozklad.notification.email.MailModels.TestMail;
import ua.vtc.rozklad.notification.email.MailModels.UnreachableMail;
import ua.vtc.rozklad.notification.text.Plurals;
import ua.vtc.rozklad.notification.text.UaDates;

/**
 * Ukrainian wording of every letter (subject, title, details, what to do, the link). Plain text only: the
 * templates escape it for HTML. The details read like the Telegram messages, so a teacher who gets both
 * recognises the same substitution.
 *
 * <p>Subjects read «what: which lesson» («Вам поставили заміну: пн, 16 листопада, 3 пара, 4КІ1»); dispatcher
 * letters put the teacher first («Відмова від заміни: Непийвода М.В., пн, 16 листопада, 3 пара, 4КІ1»). The
 * wording never assumes the teacher's gender, and never says whether a reply to the letter is read: with
 * {@code MAIL_REPLY_TO} set it reaches a person, without it nobody.
 */
@Component
public class MailTexts {

    static final String GREETING = "Добрий день!";
    static final String NOTICE_HINT = "Якщо не можете — натисніть «Не можу» у кабінеті або в Telegram, "
            + "диспетчер обере інший варіант.";
    static final String REQUEST_HINT = "Підтвердити або відмовитися можна в кабінеті (або в Telegram).";
    static final String CONFIRMED_HINT = "Заміна вже підтверджена, відповідати не потрібно.";
    static final String REVIEW_RESULT = "Перегляньте результат і застосуйте як чернетку.";
    static final String REVIEW_CONFLICTS = "Перегляньте конфлікти перед застосуванням.";
    static final String TOP_PENALTIES = "Найбільше порушень:";
    private static final String MUTUAL = "MUTUAL";
    private static final String REPLACE_DISCIPLINE = "REPLACE_DISCIPLINE";
    private static final int MAX_ERROR = 300;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    /** {@code java.lang.IllegalStateException: } at the start of an error text. */
    private static final Pattern JAVA_EXCEPTION = Pattern.compile(
            "^(?:[a-z_$][\\w$]*\\.)+([A-Z][\\w$]*(?:Exception|Error))(?::\\s*)?");

    private final MailLinks links;

    public MailTexts(MailLinks links) {
        this.links = links;
    }

    /**
     * @param kind the kind to write (a pending letter replaced by a newer one keeps its own kind)
     */
    public MailContent compose(EmailKind kind, MailModel model) {
        String settings = links.settings(settingsPage(kind, model));
        return switch (model) {
            case SubstitutionMail mail -> substitution(kind, mail, settings);
            case GenerationMail mail -> generation(mail, settings);
            case AdminSubstitutionMail mail -> adminSubstitution(kind, mail, settings);
            case AbsenceMail mail -> absence(mail, settings);
            case UnreachableMail mail -> unreachable(mail, settings);
            case OrderMail mail -> order(mail, settings);
            case RelinkedMail mail -> relinked(mail, settings);
            case TestMail ignored -> new MailContent("Тестовий лист від «Розклад ВТФК»",
                    "Листи з розкладу доходять на цю адресу.", "Тестовий лист", GREETING,
                    List.of("Це тестовий лист. Якщо ви його бачите, листи з розкладу доходять на цю адресу."),
                    List.of(), false, null, List.of(), List.of(), null, List.of(), settings);
        };
    }

    /** The settings page in the footer: the dispatcher's for his letters and for a test letter sent from there. */
    static EmailAudience settingsPage(EmailKind kind, MailModel model) {
        boolean dispatcher = kind.audience() == EmailAudience.ADMIN
                || model instanceof TestMail test && test.dispatcher();
        return dispatcher ? EmailAudience.ADMIN : EmailAudience.TEACHER;
    }

    // ---- teacher: substitutions -------------------------------------------------------------------------------

    /** The facts (date, pair, group, room) come right after the greeting: the title already says what happened. */
    private MailContent substitution(EmailKind kind, SubstitutionMail mail, String settings) {
        String about = lessonSummary(mail.date(), mail.pair(), mail.groupCode());
        List<Detail> details = details(mail);
        Link cabinet = new Link("Відкрити кабінет", links.cabinet(mail.changeId()));
        return switch (kind) {
            case SUBSTITUTION_NOTICE -> new MailContent("Вам поставили заміну: " + about, preheader(mail),
                    "Вам поставили заміну", GREETING, List.of(), details, false, null, List.of(),
                    List.of(NOTICE_HINT), cabinet, List.of(), settings);
            case SUBSTITUTION_REQUEST -> {
                List<Detail> withDeadline = withDeadline(details, mail.deadline());
                String subject = mail.deadline() == null ? "Підтвердіть заміну: " + about
                        : "Підтвердіть заміну до " + deadlineShort(mail.deadline(), mail.date()) + " — " + about;
                yield new MailContent(subject, preheader(mail), "Прохання про заміну", GREETING, List.of(),
                        withDeadline, false, null, List.of(), List.of(REQUEST_HINT), cabinet, List.of(), settings);
            }
            case SUBSTITUTION_CHANGED -> {
                String changed = mail.whatChanged() == null || mail.whatChanged().isBlank() ? "Змінено деталі заміни."
                        : mail.whatChanged() + ".";
                String hint;
                List<Detail> shown = details;
                if (mail.notice()) {
                    hint = NOTICE_HINT;
                } else if (mail.confirmed()) {
                    hint = CONFIRMED_HINT;
                } else {
                    hint = REQUEST_HINT;
                    shown = withDeadline(details, mail.deadline());
                }
                yield new MailContent("Заміну змінено: " + about, changed, "Заміну змінено", GREETING,
                        List.of(changed), shown, false, null, List.of(), List.of(hint), cabinet, List.of(), settings);
            }
            case SUBSTITUTION_CANCELLED -> {
                String title = mail.reassigned() ? "Заміну передано іншому викладачу" : "Заміну скасовано";
                String sentence = mail.reassigned()
                        ? "Цю заміну передано іншому викладачу. Нічого робити не потрібно."
                        : "Диспетчер скасував цю заміну. Нічого робити не потрібно.";
                yield new MailContent(title + ": " + about, sentence, title, GREETING, List.of(sentence), details,
                        true, null, List.of(), List.of(), new Link("Відкрити кабінет", links.cabinet()), List.of(),
                        settings);
            }
            case SUBSTITUTION_REMINDER -> new MailContent("Нагадування про заміну: " + about,
                    "Ви ще не відповіли на прохання про заміну.", "Нагадування: потрібна ваша відповідь", GREETING,
                    List.of(), withDeadline(details, mail.deadline()), false, null, List.of(),
                    List.of("Підтвердіть або відмовтеся в кабінеті (або в Telegram), щоб диспетчер знав, "
                            + "чи шукати іншого викладача."), cabinet, List.of(), settings);
            default -> throw new IllegalArgumentException(kind + " is not a substitution letter");
        };
    }

    /** The Telegram details as label–value rows, always in one order: Дата, Пара, Група, Дисципліна, Аудиторія, Замість. */
    static List<Detail> details(SubstitutionMail mail) {
        List<Detail> details = new ArrayList<>();
        details.add(new Detail("Дата", UaDates.capitalize(UaDates.weekdayDayMonth(mail.date()))));
        String time = UaDates.timeRange(mail.startsAt(), mail.endsAt());
        details.add(new Detail("Пара", mail.pair() + " пара" + (time.isEmpty() ? "" : " · " + time)));
        if (notBlank(mail.groupCode())) {
            details.add(new Detail("Група", mail.groupCode()
                    + (mail.subgroup() > 0 ? " (" + mail.subgroup() + " підгрупа)" : "")));
        }
        if (notBlank(mail.disciplineName())) {
            details.add(new Detail("Дисципліна", mail.disciplineName()));
        }
        if (notBlank(mail.roomName())) {
            details.add(new Detail("Аудиторія", mail.roomName()));
        }
        if (REPLACE_DISCIPLINE.equals(mail.changeKind()) && notBlank(mail.originalDisciplineName())) {
            StringBuilder instead = new StringBuilder("«").append(mail.originalDisciplineName()).append('»');
            if (notBlank(mail.originalTeacherName())) {
                instead.append(" (").append(mail.originalTeacherName()).append(')');
            }
            if (MUTUAL.equals(mail.reasonCode())) {
                instead.append(" — взаємна заміна");
            }
            details.add(new Detail("Замість", instead.toString()));
        } else if (notBlank(mail.originalTeacherName())) {
            details.add(new Detail("Замість", mail.originalTeacherName()));
        }
        return details;
    }

    private static List<Detail> withDeadline(List<Detail> details, Instant deadline) {
        if (deadline == null) {
            return details;
        }
        List<Detail> result = new ArrayList<>(details);
        result.add(new Detail("Відповісти до", deadlineLong(deadline)));
        return result;
    }

    private static String preheader(SubstitutionMail mail) {
        StringBuilder text = new StringBuilder();
        if (notBlank(mail.disciplineName())) {
            text.append(mail.disciplineName());
        }
        if (notBlank(mail.roomName())) {
            text.append(text.isEmpty() ? "" : ", ").append("ауд. ").append(mail.roomName());
        }
        return text.isEmpty() ? lessonSummary(mail.date(), mail.pair(), mail.groupCode()) : text.toString();
    }

    // ---- dispatchers -------------------------------------------------------------------------------------------

    /**
     * A finished generation. Hard violations are clashes (a teacher, a group or a room twice at once, a room of
     * the wrong type…): a timetable with them is «готово з конфліктами», as in the web panel, and is not to be
     * applied as it is. A job the dispatcher stopped keeps its best timetable when it had one.
     */
    private MailContent generation(GenerationMail mail, String settings) {
        Link open = new Link("Відкрити генератор", links.adminGeneration(mail.jobId()));
        boolean stopped = "CANCELLED".equals(mail.status());
        if ("FAILED".equals(mail.status())) {
            List<String> intro = new ArrayList<>();
            intro.add("Генератор не зміг скласти розклад.");
            String reason = failureReason(mail.error());
            if (reason != null) {
                intro.add("Причина: " + reason);
            }
            return new MailContent("Генерація розкладу не вдалася", intro.getLast(), "Генерація розкладу не вдалася",
                    GREETING, intro, List.of(), false, null, List.of(),
                    List.of("Перевірте дані й запустіть генерацію ще раз."), open, List.of(), settings);
        }
        if (stopped && !mail.keptBest()) {
            String sentence = "Генерацію зупинили до того, як з’явився повний розклад, тож нічого не збережено.";
            return new MailContent("Генерацію розкладу скасовано", sentence, "Генерацію розкладу скасовано",
                    GREETING, List.of(sentence), List.of(), false, null, List.of(), List.of(), open, List.of(),
                    settings);
        }
        boolean conflicts = mail.hardScore() > 0;
        String hard = mail.hardScore() + " " + Plurals.of(mail.hardScore(), "жорстке порушення", "жорсткі порушення",
                "жорстких порушень");
        String subject;
        String title;
        List<String> intro = new ArrayList<>();
        if (stopped) {
            title = "Генерацію розкладу зупинено";
            subject = conflicts ? "Генерацію зупинено: у збереженому розкладі " + hard
                    : "Генерацію зупинено: найкращий розклад збережено, штраф " + mail.softScore();
            intro.add("Генерацію зупинили вручну; найкращий знайдений розклад збережено.");
        } else {
            title = conflicts ? "Розклад згенеровано з конфліктами" : "Генерацію розкладу завершено";
            subject = conflicts ? "Розклад згенеровано з конфліктами: " + hard
                    : "Розклад згенеровано без конфліктів, штраф " + mail.softScore();
            intro.add(conflicts ? "Генератор склав розклад, але в ньому лишилися конфлікти."
                    : "Генератор склав розклад без конфліктів.");
            String why = stopSentence(mail.stopReason());
            if (why != null) {
                intro.add(why);
            }
        }
        if (conflicts) {
            intro.add("Жорсткі порушення — це накладки викладачів, груп чи аудиторій та інші правила, без яких "
                    + "розклад не можна застосувати як є.");
        }
        List<String> items = mail.penalties().stream()
                .map(penalty -> penalty.constraint() + ": " + penalty.count() + " "
                        + Plurals.of(penalty.count(), "випадок", "випадки", "випадків"))
                .toList();
        return new MailContent(subject, intro.getFirst(), title, GREETING, intro,
                List.of(new Detail("Жорсткі порушення", Long.toString(mail.hardScore())),
                        new Detail("Штраф м’яких обмежень", Long.toString(mail.softScore()))),
                false, items.isEmpty() ? null : TOP_PENALTIES, items,
                List.of(conflicts ? REVIEW_CONFLICTS : REVIEW_RESULT), open, List.of(), settings);
    }

    /** Why a job with a timetable stopped, as the web panel says it; null when unknown or not worth saying. */
    private static String stopSentence(String stopReason) {
        if (stopReason == null) {
            return null;
        }
        return switch (stopReason) {
            case "NO_IMPROVEMENT" -> "Генерація зупинилась сама: розклад перестав покращуватися.";
            case "SAFETY_CAP" -> "Генерація зупинилась на найдовшому часі, який дозволяє сервер; розклад ще міг "
                    + "покращитися.";
            case "TIME_LIMIT" -> "Генерація зупинилась на заданому ліміті часу.";
            default -> null;
        };
    }

    /** The error without a leading Java class name («java.lang.IllegalStateException: »), capped. */
    static String failureReason(String error) {
        if (!notBlank(error)) {
            return null;
        }
        String text = error.strip();
        Matcher exception = JAVA_EXCEPTION.matcher(text);
        if (exception.find()) {
            text = text.substring(exception.end()).strip();
            if (text.isEmpty()) {
                return "внутрішня помилка генератора (" + exception.group(1) + ").";
            }
        }
        return truncate(text, MAX_ERROR);
    }

    private MailContent adminSubstitution(EmailKind kind, AdminSubstitutionMail mail, String settings) {
        String teacher = notBlank(mail.teacherName()) ? mail.teacherName() : "Викладач";
        String about = teacher + adminLesson(mail);
        Link open = new Link("Відкрити зміни", links.adminChanges(mail.date()));
        List<Detail> details = adminDetails(mail);
        return switch (kind) {
            case SUBSTITUTION_DECLINED -> {
                String sentence = cannotTake(teacher, mail) + ".";
                yield new MailContent("Відмова від заміни: " + about, sentence, "Відмова від заміни", GREETING,
                        List.of(sentence), details, false, null, List.of(),
                        List.of("Оберіть інший варіант на сторінці змін."), open, List.of(), settings);
            }
            case SUBSTITUTION_OBJECTION -> {
                String sentence = cannotTake(teacher, mail) + ", а заміна вже в опублікованому розпорядженні.";
                yield new MailContent("Потрібне доповнення до розпорядження: " + about, sentence,
                        "Потрібне доповнення до розпорядження", GREETING, List.of(sentence), details, false, null,
                        List.of(), List.of("Позначте викладача відсутнім на цій парі й оберіть інший варіант — "
                        + "він піде в доповнення."), open, List.of(), settings);
            }
            case REQUEST_OVERDUE -> {
                String sentence = "Немає відповіді від " + teacher
                        + (mail.deadline() == null ? " на прохання про заміну."
                        : " до " + deadlineLong(mail.deadline()) + ".");
                yield new MailContent("Немає відповіді на запит заміни: " + about, sentence,
                        "Немає відповіді на запит заміни", GREETING, List.of(sentence), details, false, null,
                        List.of(), List.of("Підтвердіть за викладача або оберіть інший варіант."), open, List.of(),
                        settings);
            }
            default -> throw new IllegalArgumentException(kind + " is not a dispatcher substitution letter");
        };
    }

    private static List<Detail> adminDetails(AdminSubstitutionMail mail) {
        List<Detail> details = new ArrayList<>();
        if (mail.date() != null) {
            details.add(new Detail("Дата", UaDates.capitalize(UaDates.weekdayDayMonth(mail.date()))));
        }
        if (mail.pair() != null) {
            details.add(new Detail("Пара", mail.pair() + " пара"));
        }
        if (notBlank(mail.groupCode())) {
            details.add(new Detail("Група", mail.groupCode()));
        }
        if (notBlank(mail.disciplineName())) {
            details.add(new Detail("Дисципліна", mail.disciplineName()));
        }
        return details;
    }

    /** «Непийвода М.В. не може взяти 2 пару 4КІ1 у понеділок, 19 жовтня». */
    private static String cannotTake(String teacher, AdminSubstitutionMail mail) {
        StringBuilder text = new StringBuilder(teacher).append(" не може взяти ")
                .append(mail.pair() == null ? "пару" : mail.pair() + " пару");
        if (notBlank(mail.groupCode())) {
            text.append(' ').append(mail.groupCode());
        }
        if (mail.date() != null) {
            text.append(" у ").append(UaDates.weekdayDayMonthAccusative(mail.date()));
        }
        return text.toString();
    }

    private MailContent absence(AbsenceMail mail, String settings) {
        String teacher = notBlank(mail.teacherName()) ? mail.teacherName() : "Викладач";
        String dates = UaDates.range(mail.dateFrom(), mail.dateTo());
        return new MailContent("Відсутність: " + teacher + ", " + dates, teacher + ", " + dates + ". Підберіть заміни.",
                "Відсутність викладача", GREETING, List.of(),
                List.of(new Detail("Викладач", teacher), new Detail("Дати", dates), new Detail("Пари", mail.pairs()),
                        new Detail("Джерело", mail.source())), false, null, List.of(), List.of("Підберіть заміни."),
                new Link("Відкрити зміни", links.adminChanges(mail.dateFrom())), List.of(), settings);
    }

    private MailContent unreachable(UnreachableMail mail, String settings) {
        String teacher = notBlank(mail.teacherName()) ? mail.teacherName() : "Викладач";
        String about = teacher + ", " + weekdayShort(mail.date()) + ", " + UaDates.dayMonth(mail.date());
        String subject = mail.notice() ? "Повідомте про заміну самі: " + about
                : "Запит на заміну не надіслано: " + about;
        String sentence = mail.notice()
                ? "Telegram у викладача не підключено, тож повідомлення про заміну не надіслано."
                : "Telegram у викладача не підключено, тож запит на заміну не надіслано.";
        return new MailContent(subject, sentence, "Telegram не підключено", GREETING, List.of(sentence),
                List.of(new Detail("Викладач", teacher),
                        new Detail("Дата", UaDates.capitalize(UaDates.weekdayDayMonth(mail.date())))),
                false, mail.lessons().isEmpty() ? null : "Пари:", mail.lessons(),
                List.of(mail.notice() ? "Повідомте викладача самі."
                        : "Підтвердьте заміну в панелі або зв’яжіться з викладачем."),
                new Link("Відкрити зміни", links.adminChanges(mail.date())), List.of(), settings);
    }

    // ---- teacher: order, security -----------------------------------------------------------------------------

    private MailContent order(OrderMail mail, String settings) {
        String label = (mail.amendment() ? "Доповнення" : "Розпорядження")
                + (mail.number() == null ? "" : " №" + mail.number());
        String parity = notBlank(mail.parityLabel()) ? " (" + mail.parityLabel().toLowerCase(Locale.forLanguageTag("uk"))
                + ")" : "";
        String intro = "Опубліковано " + label.toLowerCase(Locale.forLanguageTag("uk")) + " на "
                + UaDates.weekdayDayMonthAccusative(mail.date()) + parity + ".";
        return new MailContent(label + " на " + UaDates.dayMonth(mail.date()) + ": ваші зміни", intro,
                "Ваші зміни в розпорядженні", GREETING, List.of(intro), List.of(), false, "Рядки, що стосуються вас:",
                mail.rows(), List.of(), new Link("Відкрити заміни на сайті", links.changes(mail.date())),
                List.of(new Link("PDF розпорядження", links.orderPdf(mail.orderId()))), settings);
    }

    private MailContent relinked(RelinkedMail mail, String settings) {
        String when = mail.at() == null ? "Щойно"
                : LocalDateTime.ofInstant(mail.at(), InfrastructureConfig.KYIV).format(DATE_TIME);
        String who = notBlank(mail.telegramAccount()) ? " (обліковий запис Telegram: " + mail.telegramAccount() + ")" : "";
        String sentence = when + " ваш Telegram у «Розклад ВТФК» підключили в іншому чаті" + who
                + ". Прохання про заміни тепер надходять туди.";
        return new MailContent("Ваш Telegram у «Розклад ВТФК» підключено в іншому чаті", sentence,
                "Telegram підключено в іншому чаті", GREETING, List.of(sentence), List.of(), false, null, List.of(),
                List.of("Якщо це зробили не ви — підключіть Telegram знову в кабінеті й повідомте диспетчера."),
                new Link("Відкрити кабінет", links.cabinet()), List.of(), settings);
    }

    // ---- shared wording ---------------------------------------------------------------------------------------

    /** «пн, 16 листопада, 3 пара, 4КІ1». */
    static String lessonSummary(LocalDate date, int pair, String groupCode) {
        return weekdayShort(date) + ", " + UaDates.dayMonth(date) + ", " + pair + " пара"
                + (notBlank(groupCode) ? ", " + groupCode : "");
    }

    /** «, пн, 16 листопада, 3 пара, 4КІ1» after the teacher's name; parts the event lacks are left out. */
    private static String adminLesson(AdminSubstitutionMail mail) {
        StringBuilder text = new StringBuilder();
        if (mail.date() != null) {
            text.append(", ").append(weekdayShort(mail.date())).append(", ").append(UaDates.dayMonth(mail.date()));
        }
        if (mail.pair() != null) {
            text.append(", ").append(mail.pair()).append(" пара");
        }
        if (notBlank(mail.groupCode())) {
            text.append(", ").append(mail.groupCode());
        }
        return text.toString();
    }

    /** «пн». */
    static String weekdayShort(LocalDate date) {
        return UaDates.weekdayShort(date).toLowerCase(Locale.forLanguageTag("uk"));
    }

    /** «пн, 16 листопада, 14:30» in Kyiv time. */
    static String deadlineLong(Instant deadline) {
        LocalDateTime local = LocalDateTime.ofInstant(deadline, InfrastructureConfig.KYIV);
        return weekdayShort(local.toLocalDate()) + ", " + UaDates.dayMonth(local.toLocalDate()) + ", "
                + local.toLocalTime().format(TIME);
    }

    /** «14:30» on the lesson's day, otherwise «15.11, 14:30». */
    static String deadlineShort(Instant deadline, LocalDate lessonDate) {
        LocalDateTime local = LocalDateTime.ofInstant(deadline, InfrastructureConfig.KYIV);
        String time = local.toLocalTime().format(TIME);
        return local.toLocalDate().equals(lessonDate) ? time : UaDates.shortDate(local.toLocalDate()) + ", " + time;
    }

    /** Top penalties of a generation: by match count, rewards excluded, at most five. */
    public static List<Penalty> topPenalties(List<Penalty> all) {
        return all.stream()
                .filter(penalty -> penalty.count() > 0)
                .sorted((a, b) -> Integer.compare(b.count(), a.count()))
                .limit(5)
                .toList();
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static boolean notBlank(String text) {
        return text != null && !text.isBlank();
    }
}
