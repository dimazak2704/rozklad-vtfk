package ua.vtc.rozklad.notification.email;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 */
@Component
public class MailTexts {

    static final String NOTICE_HINT = "Якщо не можете — натисніть «Не можу» у кабінеті або в Telegram, "
            + "диспетчер обере інший варіант.";
    static final String REQUEST_HINT = "Підтвердити або відмовитися можна в кабінеті (або в Telegram).";
    static final String CONFIRMED_HINT = "Заміна вже підтверджена, відповідати не потрібно.";
    static final String NO_REPLY = "Відповідь на цей лист ніхто не прочитає: відповідайте в кабінеті або в Telegram.";
    private static final String MUTUAL = "MUTUAL";
    private static final String REPLACE_DISCIPLINE = "REPLACE_DISCIPLINE";
    private static final int MAX_ERROR = 300;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final MailLinks links;

    public MailTexts(MailLinks links) {
        this.links = links;
    }

    /**
     * @param kind          the kind to write (a pending letter replaced by a newer one keeps its own kind)
     * @param recipientName the person's name for the greeting, or null
     */
    public MailContent compose(EmailKind kind, MailModel model, String recipientName) {
        String greeting = recipientName == null || recipientName.isBlank()
                ? "Добрий день!" : "Добрий день, " + recipientName.strip() + "!";
        String settings = links.settings(kind.audience() == EmailAudience.ADMIN ? EmailAudience.ADMIN
                : EmailAudience.TEACHER);
        return switch (model) {
            case SubstitutionMail mail -> substitution(kind, mail, greeting, settings);
            case GenerationMail mail -> generation(mail, greeting, settings);
            case AdminSubstitutionMail mail -> adminSubstitution(kind, mail, greeting, settings);
            case AbsenceMail mail -> absence(mail, greeting, settings);
            case UnreachableMail mail -> unreachable(mail, greeting, settings);
            case OrderMail mail -> order(mail, greeting, settings);
            case RelinkedMail mail -> relinked(mail, greeting, settings);
            case TestMail ignored -> new MailContent("Тестовий лист від «Розклад ВТФК»",
                    "Листи з розкладу доходять на цю адресу.", "Тестовий лист", greeting,
                    List.of("Це тестовий лист. Якщо ви його бачите, листи з розкладу доходять на цю адресу."),
                    List.of(), false, List.of(), List.of(), null, List.of(), settings);
        };
    }

    // ---- teacher: substitutions -------------------------------------------------------------------------------

    private MailContent substitution(EmailKind kind, SubstitutionMail mail, String greeting, String settings) {
        String about = lessonSummary(mail.date(), mail.pair(), mail.groupCode());
        List<Detail> details = details(mail);
        Link cabinet = new Link("Відкрити кабінет", links.cabinet(mail.changeId()));
        return switch (kind) {
            case SUBSTITUTION_NOTICE -> new MailContent("Вам поставили заміну: " + about, preheader(mail),
                    "Вам поставили заміну", greeting, List.of("Диспетчер поставив вам заміну:"), details, false,
                    List.of(), List.of(NOTICE_HINT, NO_REPLY), cabinet, List.of(), settings);
            case SUBSTITUTION_REQUEST -> {
                List<Detail> withDeadline = withDeadline(details, mail.deadline(), "Відповісти до");
                String subject = mail.deadline() == null ? "Підтвердіть заміну: " + about
                        : "Підтвердіть заміну до " + deadlineShort(mail.deadline(), mail.date()) + ": " + about;
                yield new MailContent(subject, preheader(mail), "Прохання про заміну", greeting,
                        List.of("Диспетчер просить вас узяти заміну:"), withDeadline, false, List.of(),
                        List.of(REQUEST_HINT, NO_REPLY), cabinet, List.of(), settings);
            }
            case SUBSTITUTION_CHANGED -> {
                List<String> intro = new ArrayList<>();
                intro.add(mail.whatChanged() == null || mail.whatChanged().isBlank() ? "Змінено деталі заміни."
                        : mail.whatChanged() + ".");
                String hint;
                List<Detail> shown = details;
                if (mail.notice()) {
                    hint = NOTICE_HINT;
                } else if (mail.confirmed()) {
                    hint = CONFIRMED_HINT;
                } else {
                    hint = REQUEST_HINT;
                    shown = withDeadline(details, mail.deadline(), "Відповісти до");
                }
                yield new MailContent("Заміну змінено: " + about, intro.getFirst(), "Заміну змінено", greeting,
                        intro, shown, false, List.of(), List.of(hint, NO_REPLY), cabinet, List.of(), settings);
            }
            case SUBSTITUTION_CANCELLED -> {
                String title = mail.reassigned() ? "Заміну передано іншому викладачу" : "Заміну скасовано";
                String sentence = mail.reassigned()
                        ? "Цю заміну передано іншому викладачу. Нічого робити не потрібно."
                        : "Диспетчер скасував цю заміну. Нічого робити не потрібно.";
                yield new MailContent(title + ": " + about, sentence, title, greeting, List.of(sentence), details,
                        true, List.of(), List.of(), new Link("Відкрити кабінет", links.cabinet()), List.of(),
                        settings);
            }
            case SUBSTITUTION_REMINDER -> new MailContent("Нагадування: підтвердіть заміну — " + about,
                    "Ви ще не відповіли на прохання про заміну.", "Нагадування: потрібна ваша відповідь", greeting,
                    List.of("Ви ще не відповіли на прохання про заміну."),
                    withDeadline(details, mail.deadline(), "Строк відповіді"), false, List.of(),
                    List.of("Підтвердіть або відмовтеся в кабінеті (або в Telegram), щоб диспетчер знав, "
                            + "чи шукати іншого викладача.", NO_REPLY), cabinet, List.of(), settings);
            default -> throw new IllegalArgumentException(kind + " is not a substitution letter");
        };
    }

    /** The Telegram details as label–value rows. */
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
        boolean otherDiscipline = REPLACE_DISCIPLINE.equals(mail.changeKind())
                && notBlank(mail.originalDisciplineName());
        if (otherDiscipline) {
            StringBuilder instead = new StringBuilder("«").append(mail.originalDisciplineName()).append('»');
            if (notBlank(mail.originalTeacherName())) {
                instead.append(" (").append(mail.originalTeacherName()).append(')');
            }
            if (MUTUAL.equals(mail.reasonCode())) {
                instead.append(" — взаємна заміна");
            }
            details.add(new Detail("Замість", instead.toString()));
        }
        if (notBlank(mail.roomName())) {
            details.add(new Detail("Аудиторія", mail.roomName()));
        }
        if (!otherDiscipline && notBlank(mail.originalTeacherName())) {
            details.add(new Detail("Замість", mail.originalTeacherName()));
        }
        return details;
    }

    private static List<Detail> withDeadline(List<Detail> details, Instant deadline, String label) {
        if (deadline == null) {
            return details;
        }
        List<Detail> result = new ArrayList<>(details);
        result.add(new Detail(label, deadlineLong(deadline)));
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

    private MailContent generation(GenerationMail mail, String greeting, String settings) {
        Link open = new Link("Відкрити генератор", links.adminGeneration(mail.jobId()));
        return switch (mail.status()) {
            case "SOLVED" -> {
                String hard = mail.hardScore() + " " + Plurals.of(mail.hardScore(), "жорстке порушення",
                        "жорсткі порушення", "жорстких порушень");
                List<String> items = mail.penalties().stream()
                        .map(penalty -> penalty.constraint() + " — " + penalty.count())
                        .toList();
                List<String> intro = new ArrayList<>();
                intro.add("Генератор склав розклад.");
                if (!items.isEmpty()) {
                    intro.add("Найбільше порушень:");
                }
                yield new MailContent("Розклад згенеровано: " + hard + ", штраф " + mail.softScore(),
                        "Перегляньте результат і застосуйте як чернетку.", "Генерацію розкладу завершено", greeting,
                        intro, List.of(new Detail("Жорсткі порушення", Long.toString(mail.hardScore())),
                        new Detail("Штраф м’яких обмежень", Long.toString(mail.softScore()))), false, items,
                        List.of("Перегляньте результат і застосуйте як чернетку."), open, List.of(), settings);
            }
            case "FAILED" -> {
                List<String> intro = new ArrayList<>();
                intro.add("Генератор не зміг скласти розклад.");
                if (notBlank(mail.error())) {
                    intro.add("Помилка: " + truncate(mail.error().strip(), MAX_ERROR));
                }
                yield new MailContent("Генерація розкладу не вдалася", intro.getLast(),
                        "Генерація розкладу не вдалася", greeting, intro, List.of(), false, List.of(),
                        List.of("Перевірте дані й запустіть генерацію ще раз."), open, List.of(), settings);
            }
            default -> new MailContent("Генерацію розкладу зупинено", "Генерацію зупинили до завершення.",
                    "Генерацію розкладу зупинено", greeting, List.of("Генерацію зупинили до завершення."), List.of(),
                    false, List.of(), List.of(), open, List.of(), settings);
        };
    }

    private MailContent adminSubstitution(EmailKind kind, AdminSubstitutionMail mail, String greeting,
                                          String settings) {
        String teacher = notBlank(mail.teacherName()) ? mail.teacherName() : "Викладач";
        String about = teacher + ", " + adminLesson(mail.pair(), mail.groupCode())
                + (mail.date() == null ? "" : ", " + weekdayShort(mail.date()) + " " + UaDates.shortDate(mail.date()));
        Link open = new Link("Відкрити зміни", links.adminChanges(mail.date()));
        List<Detail> details = adminDetails(mail);
        return switch (kind) {
            case SUBSTITUTION_DECLINED -> {
                String sentence = cannotTake(teacher, mail) + ".";
                yield new MailContent("Відмова від заміни: " + about, sentence, "Відмова від заміни", greeting,
                        List.of(sentence), details, false, List.of(),
                        List.of("Оберіть інший варіант на сторінці змін."), open, List.of(), settings);
            }
            case SUBSTITUTION_OBJECTION -> {
                String sentence = cannotTake(teacher, mail) + ", а заміна вже в опублікованому розпорядженні.";
                yield new MailContent("Потрібне доповнення до розпорядження: " + about, sentence,
                        "Потрібне доповнення до розпорядження", greeting, List.of(sentence), details, false,
                        List.of(), List.of("Позначте викладача відсутнім на цій парі й оберіть інший варіант — "
                        + "він піде в доповнення."), open, List.of(), settings);
            }
            case REQUEST_OVERDUE -> {
                String sentence = teacher + " не відповів на прохання про заміну"
                        + (mail.deadline() == null ? "." : " до " + deadlineLong(mail.deadline()) + ".");
                yield new MailContent("Немає відповіді на запит заміни: " + about, sentence,
                        "Немає відповіді на запит заміни", greeting, List.of(sentence), details, false, List.of(),
                        List.of("Підтвердіть за викладача або оберіть інший варіант."), open, List.of(), settings);
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

    private MailContent absence(AbsenceMail mail, String greeting, String settings) {
        String teacher = notBlank(mail.teacherName()) ? mail.teacherName() : "Викладач";
        String dates = UaDates.range(mail.dateFrom(), mail.dateTo());
        return new MailContent("Відсутність: " + teacher + ", " + dates, "Підберіть заміни.",
                "Відсутність викладача", greeting, List.of(teacher + " буде відсутній."),
                List.of(new Detail("Дати", dates), new Detail("Пари", mail.pairs()),
                        new Detail("Джерело", mail.source())), false, List.of(), List.of("Підберіть заміни."),
                new Link("Відкрити зміни", links.adminChanges(mail.dateFrom())), List.of(), settings);
    }

    private MailContent unreachable(UnreachableMail mail, String greeting, String settings) {
        String teacher = notBlank(mail.teacherName()) ? mail.teacherName() : "Викладач";
        String day = UaDates.weekdayDayMonth(mail.date());
        String subject = mail.notice()
                ? teacher + " не підключив Telegram — повідомте про заміну самі"
                : teacher + " не підключив Telegram — запит на заміну не надіслано";
        String intro = mail.notice()
                ? "Повідомлення про заміну в Telegram не надіслано (" + day + "):"
                : "Запит на заміну в Telegram не надіслано (" + day + "):";
        return new MailContent(subject, intro, teacher + " не підключив Telegram", greeting, List.of(intro), List.of(),
                false, mail.lessons(), List.of(mail.notice() ? "Повідомте викладача самі."
                : "Підтвердьте заміну в панелі або зв’яжіться з викладачем."),
                new Link("Відкрити зміни", links.adminChanges(mail.date())), List.of(), settings);
    }

    // ---- teacher: order, security -----------------------------------------------------------------------------

    private MailContent order(OrderMail mail, String greeting, String settings) {
        String label = (mail.amendment() ? "Доповнення" : "Розпорядження")
                + (mail.number() == null ? "" : " №" + mail.number());
        String parity = notBlank(mail.parityLabel()) ? " (" + mail.parityLabel().toLowerCase(Locale.forLanguageTag("uk"))
                + ")" : "";
        String intro = "Опубліковано " + label.toLowerCase(Locale.forLanguageTag("uk")) + " на "
                + UaDates.weekdayDayMonthAccusative(mail.date()) + parity + ". Рядки, що стосуються вас:";
        return new MailContent(label + " на " + UaDates.dayMonth(mail.date()) + ": ваші зміни", intro,
                "Ваші зміни в розпорядженні", greeting, List.of(intro), List.of(), false, mail.rows(), List.of(),
                new Link("Відкрити заміни на сайті", links.changes(mail.date())),
                List.of(new Link("PDF розпорядження", links.orderPdf(mail.orderId()))), settings);
    }

    private MailContent relinked(RelinkedMail mail, String greeting, String settings) {
        String when = mail.at() == null ? "Щойно"
                : LocalDateTime.ofInstant(mail.at(), InfrastructureConfig.KYIV).format(DATE_TIME);
        String who = notBlank(mail.telegramAccount()) ? " (обліковий запис Telegram: " + mail.telegramAccount() + ")" : "";
        String sentence = when + " ваш Telegram у «Розклад ВТФК» підключили в іншому чаті" + who
                + ". Прохання про заміни тепер надходять туди.";
        return new MailContent("Ваш Telegram у «Розклад ВТФК» підключено в іншому чаті", sentence,
                "Telegram підключено в іншому чаті", greeting, List.of(sentence), List.of(), false, List.of(),
                List.of("Якщо це зробили не ви — підключіть Telegram знову в кабінеті й повідомте диспетчера."),
                new Link("Відкрити кабінет", links.cabinet()), List.of(), settings);
    }

    // ---- shared wording ---------------------------------------------------------------------------------------

    /** «пн, 16 листопада, 3 пара, 4КІ1». */
    static String lessonSummary(LocalDate date, int pair, String groupCode) {
        return weekdayShort(date) + ", " + UaDates.dayMonth(date) + ", " + pair + " пара"
                + (notBlank(groupCode) ? ", " + groupCode : "");
    }

    /** «3 пара 4КІ1». */
    private static String adminLesson(Integer pair, String groupCode) {
        String text = pair == null ? "пара" : pair + " пара";
        return notBlank(groupCode) ? text + " " + groupCode : text;
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
