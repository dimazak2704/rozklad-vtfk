package ua.vtc.rozklad.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.notification.config.MailConfig;
import ua.vtc.rozklad.notification.config.ServiceUrlsProperties;
import ua.vtc.rozklad.notification.email.MailModels.AbsenceMail;
import ua.vtc.rozklad.notification.email.MailModels.AdminSubstitutionMail;
import ua.vtc.rozklad.notification.email.MailModels.GenerationMail;
import ua.vtc.rozklad.notification.email.MailModels.OrderMail;
import ua.vtc.rozklad.notification.email.MailModels.Penalty;
import ua.vtc.rozklad.notification.email.MailModels.RelinkedMail;
import ua.vtc.rozklad.notification.email.MailModels.SubstitutionMail;
import ua.vtc.rozklad.notification.email.MailModels.TestMail;
import ua.vtc.rozklad.notification.email.MailModels.UnreachableMail;
import ua.vtc.rozklad.notification.email.MailTemplates.Rendered;

/** Wording, layout and escaping of every letter: subject, HTML and plain text, links. */
class MailTemplatesTest {

    private static final UUID CHANGE = UUID.fromString("6c1f0a52-3d4e-4f60-9a7b-8c9d0e1f2a01");
    private static final UUID JOB = UUID.fromString("0f9d8c1e-2a3b-4c5d-8e6f-7a8b9c0d1e2f");
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 16);
    /** 14:30 Kyiv on 16.11.2026. */
    private static final Instant DEADLINE = Instant.parse("2026-11-16T12:30:00Z");

    private final MailTexts texts = new MailTexts(new MailLinks(new ServiceUrlsProperties(URI.create("http://core"),
            URI.create("http://importer"), URI.create("https://rozklad.vtc.vn.ua/"))));
    private final MailTemplates templates = new MailTemplates(MailConfig.templateEngine());

    @Test
    void noticeToATeacherReadsLikeTheTelegramMessage() {
        Rendered letter = render(EmailKind.SUBSTITUTION_NOTICE, substitution("NOTIFY", "214", null), "Непийвода М.В.");

        assertThat(letter.subject()).isEqualTo("Вам поставили заміну: пн, 16 листопада, 3 пара, 4КІ1");
        assertThat(letter.text()).startsWith("Вам поставили заміну\n\nДобрий день, Непийвода М.В.!\n")
                .contains("Дата: Понеділок, 16 листопада\n"
                        + "Пара: 3 пара · 11:00–12:00\n"
                        + "Група: 4КІ1 (1 підгрупа)\n"
                        + "Дисципліна: Комп’ютерні мережі\n"
                        + "Аудиторія: 214\n"
                        + "Замість: Кренцін М.Д.\n")
                .contains("Якщо не можете — натисніть «Не можу» у кабінеті або в Telegram")
                .contains("Відкрити кабінет: https://rozklad.vtc.vn.ua/me?change=" + CHANGE)
                .endsWith("Налаштувати листи: https://rozklad.vtc.vn.ua/me/notifications\n");
        assertThat(letter.html())
                .contains("<html lang=\"uk\"")
                .contains("href=\"https://rozklad.vtc.vn.ua/me?change=" + CHANGE + "\"")
                .contains(">Відкрити кабінет</a>")
                .contains("Цей лист надіслала система «Розклад ВТФК» автоматично")
                .doesNotContain("<img", "http://", "src=");
    }

    @Test
    void requestNamesTheDeadlineInKyivTime() {
        Rendered letter = render(EmailKind.SUBSTITUTION_REQUEST, substitution("CONFIRM", "214", null), null);

        assertThat(letter.subject()).isEqualTo("Підтвердіть заміну до 14:30: пн, 16 листопада, 3 пара, 4КІ1");
        assertThat(letter.text()).contains("Добрий день!\n").contains("Відповісти до: пн, 16 листопада, 14:30\n")
                .contains("Підтвердити або відмовитися можна в кабінеті (або в Telegram).");
    }

    @Test
    void changedLetterSaysWhatChangedFirst() {
        Rendered notice = render(EmailKind.SUBSTITUTION_CHANGED,
                substitution("NOTIFY", "214", "Нова аудиторія: 214 (була 6)"), null);
        assertThat(notice.subject()).isEqualTo("Заміну змінено: пн, 16 листопада, 3 пара, 4КІ1");
        assertThat(notice.text()).contains("Нова аудиторія: 214 (була 6).\n\nДата:");

        SubstitutionMail confirmed = new SubstitutionMail(CHANGE, "CONFIRM", MONDAY, 3, LocalTime.of(11, 0),
                LocalTime.of(12, 0), "4КІ1", 0, "КМ", null, null, null, null, "214", DEADLINE, "Змінено: пару", true,
                false);
        assertThat(render(EmailKind.SUBSTITUTION_CHANGED, confirmed, null).text())
                .contains("Заміна вже підтверджена, відповідати не потрібно.")
                .doesNotContain("Відповісти до");
    }

    @Test
    void cancellationStrikesTheDetailsAndReassignmentSaysSo() {
        Rendered cancelled = render(EmailKind.SUBSTITUTION_CANCELLED, substitution("NOTIFY", "214", null), null);
        assertThat(cancelled.subject()).startsWith("Заміну скасовано: пн, 16 листопада");
        assertThat(cancelled.text()).contains("Диспетчер скасував цю заміну. Нічого робити не потрібно.")
                .contains("(більше не актуально)");
        assertThat(cancelled.html()).contains("text-decoration:line-through");

        Rendered reassigned = render(EmailKind.SUBSTITUTION_CANCELLED,
                substitution("NOTIFY", "214", null).asReassigned(), null);
        assertThat(reassigned.subject()).startsWith("Заміну передано іншому викладачу: ");
    }

    @Test
    void replacingAnotherDisciplineNamesItAndMutualReason() {
        SubstitutionMail mail = new SubstitutionMail(CHANGE, "NOTIFY", MONDAY, 3, null, null, "4КІ1", 0, "КМ",
                "REPLACE_DISCIPLINE", "СТП", "Кренцін М.Д.", "MUTUAL", null, null, null, false, false);
        assertThat(render(EmailKind.SUBSTITUTION_NOTICE, mail, null).text())
                .contains("Пара: 3 пара\n")
                .contains("Замість: «СТП» (Кренцін М.Д.) — взаємна заміна\n")
                .doesNotContain("Аудиторія");
    }

    @Test
    void everyValueIsEscapedInHtmlAndLeftAsIsInText() {
        SubstitutionMail hostile = new SubstitutionMail(CHANGE, "NOTIFY", MONDAY, 3, null, null,
                "4КІ1", 0, "<script>alert(1)</script> & \"Q\"", null, null, "O'Neil <b>", null, "<img src=x>", null,
                null, false, false);
        Rendered letter = render(EmailKind.SUBSTITUTION_NOTICE, hostile, "<i>Ім'я</i>");

        assertThat(letter.html()).doesNotContain("<script>", "<img src=x>", "<b>", "<i>Ім")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;Q&quot;")
                .contains("&lt;img src=x&gt;");
        assertThat(letter.text()).contains("Дисципліна: <script>alert(1)</script> & \"Q\"")
                .contains("Добрий день, <i>Ім'я</i>!")
                .doesNotContain("&lt;", "&amp;");
    }

    @Test
    void subjectHasNoLineBreaks() {
        SubstitutionMail mail = new SubstitutionMail(CHANGE, "NOTIFY", MONDAY, 3, null, null, "4КІ1\r\nBcc: x@y.z",
                0, null, null, null, null, null, null, null, null, false, false);
        Rendered letter = render(EmailKind.SUBSTITUTION_NOTICE, mail, null);
        assertThat(letter.subject()).doesNotContain("\r", "\n").endsWith("4КІ1 Bcc: x@y.z");
    }

    @Test
    void generationResultForTheDispatcher() {
        GenerationMail solved = new GenerationMail(JOB, "SOLVED", 0, 35, List.of(new Penalty("Вікна в розкладі", 12),
                new Penalty("Пари в суботу", 3)), null);
        Rendered letter = render(EmailKind.GENERATION_FINISHED, solved, "Диспетчер");

        assertThat(letter.subject()).isEqualTo("Розклад згенеровано: 0 жорстких порушень, штраф 35");
        assertThat(letter.text()).contains("Жорсткі порушення: 0\nШтраф м’яких обмежень: 35\n")
                .contains("• Вікна в розкладі — 12\n• Пари в суботу — 3\n")
                .contains("Відкрити генератор: https://rozklad.vtc.vn.ua/admin/generation?job=" + JOB)
                .endsWith("Налаштувати листи: https://rozklad.vtc.vn.ua/admin/notifications\n");

        assertThat(render(EmailKind.GENERATION_FINISHED, new GenerationMail(JOB, "SOLVED", 1, 0, List.of(), null),
                null).subject()).isEqualTo("Розклад згенеровано: 1 жорстке порушення, штраф 0");
        Rendered failed = render(EmailKind.GENERATION_FINISHED,
                new GenerationMail(JOB, "FAILED", 0, 0, List.of(), "x".repeat(400)), null);
        assertThat(failed.subject()).isEqualTo("Генерація розкладу не вдалася");
        assertThat(failed.text()).contains("Помилка: " + "x".repeat(299) + "…");
        assertThat(render(EmailKind.GENERATION_FINISHED, new GenerationMail(JOB, "CANCELLED", 0, 0, List.of(), null),
                null).subject()).isEqualTo("Генерацію розкладу зупинено");
    }

    @Test
    void topPenaltiesAreTheFiveBiggestWithoutEmptyRows() {
        List<Penalty> all = List.of(new Penalty("a", 1), new Penalty("b", 9), new Penalty("c", 0),
                new Penalty("d", 5), new Penalty("e", 7), new Penalty("f", 3), new Penalty("g", 2));
        assertThat(MailTexts.topPenalties(all)).extracting(Penalty::constraint).containsExactly("b", "e", "d", "f", "g");
    }

    @Test
    void dispatcherLettersAboutSubstitutions() {
        AdminSubstitutionMail mail = new AdminSubstitutionMail(CHANGE, "Непийвода М.В.", MONDAY, 3, "4КІ1", "КМ",
                DEADLINE);
        assertThat(render(EmailKind.SUBSTITUTION_DECLINED, mail, null).subject())
                .isEqualTo("Відмова від заміни: Непийвода М.В., 3 пара 4КІ1, пн 16.11");
        assertThat(render(EmailKind.SUBSTITUTION_DECLINED, mail, null).text())
                .contains("Непийвода М.В. не може взяти 3 пару 4КІ1 у понеділок, 16 листопада.")
                .contains("Відкрити зміни: https://rozklad.vtc.vn.ua/admin/changes?date=2026-11-16");
        assertThat(render(EmailKind.SUBSTITUTION_OBJECTION, mail, null).text())
                .contains("а заміна вже в опублікованому розпорядженні")
                .contains("Позначте викладача відсутнім на цій парі");
        assertThat(render(EmailKind.REQUEST_OVERDUE, mail, null).text())
                .contains("Непийвода М.В. не відповів на прохання про заміну до пн, 16 листопада, 14:30.");
    }

    @Test
    void absenceNeverCarriesAReasonAndUnreachableListsPairs() {
        Rendered absence = render(EmailKind.ABSENCE_REPORTED, new AbsenceMail(UUID.randomUUID(), "Кренцін М.Д.",
                MONDAY, MONDAY.plusDays(2), "увесь день", "Telegram-бот"), null);
        assertThat(absence.subject()).isEqualTo("Відсутність: Кренцін М.Д., 16–18 листопада");
        assertThat(absence.text()).contains("Пари: увесь день\nДжерело: Telegram-бот\n")
                .doesNotContain("Причина");

        Rendered unreachable = render(EmailKind.TEACHER_UNREACHABLE, new UnreachableMail(UUID.randomUUID(),
                "Кренцін М.Д.", MONDAY, true, List.of("2 пара · 4КІ1 · КМ")), null);
        assertThat(unreachable.subject()).isEqualTo("Кренцін М.Д. не підключив Telegram — повідомте про заміну самі");
        assertThat(unreachable.text()).contains("• 2 пара · 4КІ1 · КМ\n");
    }

    @Test
    void orderRowsSecurityAndTestLetters() {
        UUID order = UUID.randomUUID();
        Rendered rows = render(EmailKind.ORDER_TEACHER, new OrderMail(order, 35, false, MONDAY, "ЗНАМЕННИК",
                List.of("🔁 4КІ1, 2 пара: КМ · Непийвода М.В., ауд. 6")), null);
        assertThat(rows.subject()).isEqualTo("Розпорядження №35 на 16 листопада: ваші зміни");
        assertThat(rows.text()).contains("Опубліковано розпорядження №35 на понеділок, 16 листопада (знаменник).")
                .contains("PDF розпорядження: https://rozklad.vtc.vn.ua/api/documents/orders/" + order + ".pdf");

        Rendered relinked = render(EmailKind.SECURITY_TELEGRAM_RELINKED, new RelinkedMail("Непийвода М.В.",
                "Марія (@maria)", Instant.parse("2026-11-16T08:05:00Z")), null);
        assertThat(relinked.subject()).isEqualTo("Ваш Telegram у «Розклад ВТФК» підключено в іншому чаті");
        assertThat(relinked.text()).contains("16.11.2026 10:05 ваш Telegram у «Розклад ВТФК» підключили в іншому чаті "
                + "(обліковий запис Telegram: Марія (@maria))");

        assertThat(render(EmailKind.TEST, new TestMail(Instant.now()), null).subject())
                .isEqualTo("Тестовий лист від «Розклад ВТФК»");
    }

    @Test
    void textPartIsTidy() {
        String text = render(EmailKind.SUBSTITUTION_NOTICE, substitution("NOTIFY", null, null), null).text();
        assertThat(text).doesNotContain("\n\n\n", " \n", "[(", "th:").endsWith("\n");
    }

    private Rendered render(EmailKind kind, MailModels.MailModel model, String name) {
        return templates.render(texts.compose(kind, model, name));
    }

    private static SubstitutionMail substitution(String mode, String room, String whatChanged) {
        return new SubstitutionMail(CHANGE, mode, MONDAY, 3, LocalTime.of(11, 0), LocalTime.of(12, 0), "4КІ1", 1,
                "Комп’ютерні мережі", "REPLACE_TEACHER", null, "Кренцін М.Д.", null, room, DEADLINE, whatChanged,
                false, false);
    }
}
