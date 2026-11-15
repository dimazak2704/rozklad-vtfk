package ua.vtc.rozklad.notification.email;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import ua.vtc.rozklad.notification.email.MailModels.AbsenceMail;
import ua.vtc.rozklad.notification.email.MailModels.AdminSubstitutionMail;
import ua.vtc.rozklad.notification.email.MailModels.GenerationMail;
import ua.vtc.rozklad.notification.email.MailModels.OrderMail;
import ua.vtc.rozklad.notification.email.MailModels.RelinkedMail;
import ua.vtc.rozklad.notification.email.MailModels.SubstitutionMail;
import ua.vtc.rozklad.notification.email.MailModels.TestMail;
import ua.vtc.rozklad.notification.email.MailModels.UnreachableMail;

/**
 * Every kind of letter with its defaults (docs/modules/notification.md «E-mail»). The defaults live here and
 * only a person's deviations are stored ({@code email_preference.overrides}), so a changed default reaches
 * everyone who never touched that kind.
 *
 * <p>{@code followsChange}: a letter about a teacher's substitution. It waits {@code app.mail.settle-delay}
 * before it is sent, a newer letter of the same change replaces it while it waits, and it is checked against the
 * change right before sending ({@link EmailRelevance}).
 */
public enum EmailKind {

    SUBSTITUTION_NOTICE(EmailAudience.TEACHER, true, true, true, SubstitutionMail.class,
            "Вам поставили заміну", "Нова заміна, про яку диспетчер повідомляє (можна відповісти «Не можу»)."),
    SUBSTITUTION_REQUEST(EmailAudience.TEACHER, true, true, true, SubstitutionMail.class,
            "Прохання про заміну", "Заміна, яку треба підтвердити до строку."),
    SUBSTITUTION_CHANGED(EmailAudience.TEACHER, true, true, true, SubstitutionMail.class,
            "Заміну змінено", "Диспетчер змінив аудиторію чи інші деталі заміни, про яку ви вже отримали лист."),
    SUBSTITUTION_CANCELLED(EmailAudience.TEACHER, true, true, true, SubstitutionMail.class,
            "Заміну скасовано", "Заміну, про яку ви отримали лист, скасовано або передано іншому викладачу."),
    SUBSTITUTION_REMINDER(EmailAudience.TEACHER, true, true, true, SubstitutionMail.class,
            "Нагадування про відповідь", "Строк підтвердити заміну минув, а відповіді ще немає."),
    ORDER_TEACHER(EmailAudience.TEACHER, false, true, false, OrderMail.class,
            "Розпорядження з вашими змінами", "Рядки опублікованого розпорядження, що стосуються вас."),
    SECURITY_TELEGRAM_RELINKED(EmailAudience.TEACHER, true, false, false, RelinkedMail.class,
            "Telegram підключено в іншому чаті", "Попередження безпеки: його не можна вимкнути."),
    GENERATION_FINISHED(EmailAudience.ADMIN, true, true, false, GenerationMail.class,
            "Генерацію розкладу завершено", "Генератор закінчив роботу, не впорався або його зупинили."),
    SUBSTITUTION_DECLINED(EmailAudience.ADMIN, true, true, false, AdminSubstitutionMail.class,
            "Відмова від заміни", "Викладач не може взяти заміну: потрібен інший варіант."),
    SUBSTITUTION_OBJECTION(EmailAudience.ADMIN, true, true, false, AdminSubstitutionMail.class,
            "Потрібне доповнення до розпорядження", "Викладач не може взяти заміну з опублікованого розпорядження."),
    REQUEST_OVERDUE(EmailAudience.ADMIN, true, true, false, AdminSubstitutionMail.class,
            "Немає відповіді на запит заміни", "Строк підтвердження минув, а викладач не відповів."),
    ABSENCE_REPORTED(EmailAudience.ADMIN, false, true, false, AbsenceMail.class,
            "Відсутність викладача", "Викладач повідомив про відсутність (причина в листі не вказується)."),
    TEACHER_UNREACHABLE(EmailAudience.ADMIN, false, true, false, UnreachableMail.class,
            "Викладач без Telegram", "Викладач не підключив Telegram, тож про заміну треба сказати самим."),
    TEST(EmailAudience.SELF, true, false, false, TestMail.class,
            "Тестовий лист", "Перевірка, що листи доходять.");

    private final EmailAudience audience;
    private final boolean defaultOn;
    private final boolean switchable;
    private final boolean followsChange;
    private final Class<? extends MailModels.MailModel> model;
    private final String label;
    private final String description;

    EmailKind(EmailAudience audience, boolean defaultOn, boolean switchable, boolean followsChange,
              Class<? extends MailModels.MailModel> model, String label, String description) {
        this.audience = audience;
        this.defaultOn = defaultOn;
        this.switchable = switchable;
        this.followsChange = followsChange;
        this.model = model;
        this.label = label;
        this.description = description;
    }

    public EmailAudience audience() {
        return audience;
    }

    /** On for a person who never changed it. */
    public boolean defaultOn() {
        return defaultOn;
    }

    /** A person may switch it off (or on); a security notice and the test letter cannot be switched. */
    public boolean switchable() {
        return switchable;
    }

    public boolean followsChange() {
        return followsChange;
    }

    /** Shown on the settings page (the test letter is not a kind anybody subscribes to). */
    public boolean listed() {
        return audience != EmailAudience.SELF;
    }

    public Class<? extends MailModels.MailModel> model() {
        return model;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public static Optional<EmailKind> parse(String name) {
        return Arrays.stream(values()).filter(kind -> kind.name().equals(name)).findFirst();
    }

    public static List<EmailKind> listedFor(EmailAudience audience) {
        return Arrays.stream(values()).filter(kind -> kind.listed() && kind.audience == audience).toList();
    }
}
