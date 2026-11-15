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
        Rendered letter = render(EmailKind.SUBSTITUTION_NOTICE, substitution("NOTIFY", "214", null));

        assertThat(letter.subject()).isEqualTo("Вам поставили заміну: пн, 16 листопада, 3 пара, 4КІ1");
        // the facts come right after the greeting: the title already says what happened
        assertThat(letter.text()).startsWith("Вам поставили заміну\n\nДобрий день!\n\nДата: Понеділок, 16 листопада\n"
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
    void lettersNeverSayWhetherAReplyIsRead() {
        // MAIL_REPLY_TO may send a reply to the dispatchers' office: no letter may claim that nobody reads it
        List<Rendered> letters = List.of(
                render(EmailKind.SUBSTITUTION_NOTICE, substitution("NOTIFY", "214", null)),
                render(EmailKind.SUBSTITUTION_REQUEST, substitution("CONFIRM", "214", null)),
                render(EmailKind.SUBSTITUTION_CHANGED, substitution("CONFIRM", "214", "Нова аудиторія: 214 (була 6)")),
                render(EmailKind.SUBSTITUTION_REMINDER, substitution("CONFIRM", "214", null)),
                render(EmailKind.GENERATION_FINISHED, solved(0, 35)));
        assertThat(letters).allSatisfy(letter -> {
            assertThat(letter.text()).doesNotContain("ніхто не прочитає", "Відповідати на нього не потрібно")
                    .contains("Цей лист надіслала система «Розклад ВТФК» автоматично.\n");
            assertThat(letter.html()).doesNotContain("ніхто не прочитає", "Відповідати на нього не потрібно");
        });
    }

    @Test
    void requestNamesTheDeadlineInKyivTime() {
        Rendered letter = render(EmailKind.SUBSTITUTION_REQUEST, substitution("CONFIRM", "214", null));

        assertThat(letter.subject()).isEqualTo("Підтвердіть заміну до 14:30 — пн, 16 листопада, 3 пара, 4КІ1");
        assertThat(letter.text()).startsWith("Прохання про заміну\n\nДобрий день!\n\nДата:")
                .contains("Відповісти до: пн, 16 листопада, 14:30\n")
                .contains("Підтвердити або відмовитися можна в кабінеті (або в Telegram).");
        assertThat(render(EmailKind.SUBSTITUTION_REMINDER, substitution("CONFIRM", "214", null)).subject())
                .isEqualTo("Нагадування про заміну: пн, 16 листопада, 3 пара, 4КІ1");
    }

    @Test
    void changedLetterSaysWhatChangedFirst() {
        Rendered notice = render(EmailKind.SUBSTITUTION_CHANGED,
                substitution("NOTIFY", "214", "Нова аудиторія: 214 (була 6)"));
        assertThat(notice.subject()).isEqualTo("Заміну змінено: пн, 16 листопада, 3 пара, 4КІ1");
        assertThat(notice.text()).contains("Нова аудиторія: 214 (була 6).\n\nДата:");

        SubstitutionMail confirmed = new SubstitutionMail(CHANGE, "CONFIRM", MONDAY, 3, LocalTime.of(11, 0),
                LocalTime.of(12, 0), "4КІ1", 0, "КМ", null, null, null, null, "214", DEADLINE, "Змінено: пару", true,
                false);
        assertThat(render(EmailKind.SUBSTITUTION_CHANGED, confirmed).text())
                .contains("Заміна вже підтверджена, відповідати не потрібно.")
                .doesNotContain("Відповісти до");
    }

    @Test
    void cancellationMutesTheDetailsAndReassignmentSaysSo() {
        Rendered cancelled = render(EmailKind.SUBSTITUTION_CANCELLED, substitution("NOTIFY", "214", null));
        assertThat(cancelled.subject()).startsWith("Заміну скасовано: пн, 16 листопада");
        assertThat(cancelled.text()).contains("Диспетчер скасував цю заміну. Нічого робити не потрібно.")
                .contains("(більше не актуально)");
        // grey struck text is hard to read on a phone: muted values, the title says it is cancelled
        assertThat(cancelled.html()).doesNotContain("line-through").contains("color:#4b5563;");

        Rendered reassigned = render(EmailKind.SUBSTITUTION_CANCELLED,
                substitution("NOTIFY", "214", null).asReassigned());
        assertThat(reassigned.subject()).startsWith("Заміну передано іншому викладачу: ");
    }

    @Test
    void replacingAnotherDisciplineKeepsTheUsualRowOrder() {
        SubstitutionMail mail = new SubstitutionMail(CHANGE, "NOTIFY", MONDAY, 3, null, null, "4КІ1", 0, "КМ",
                "REPLACE_DISCIPLINE", "СТП", "Кренцін М.Д.", "MUTUAL", "214", null, null, false, false);
        assertThat(render(EmailKind.SUBSTITUTION_NOTICE, mail).text())
                .contains("Пара: 3 пара\nГрупа: 4КІ1\nДисципліна: КМ\nАудиторія: 214\n"
                        + "Замість: «СТП» (Кренцін М.Д.) — взаємна заміна\n");
        SubstitutionMail noRoom = new SubstitutionMail(CHANGE, "NOTIFY", MONDAY, 3, null, null, "4КІ1", 0, "КМ",
                "REPLACE_DISCIPLINE", "СТП", null, null, null, null, null, false, false);
        assertThat(render(EmailKind.SUBSTITUTION_NOTICE, noRoom).text())
                .contains("Дисципліна: КМ\nЗамість: «СТП»\n")
                .doesNotContain("Аудиторія");
    }

    @Test
    void everyValueIsEscapedInHtmlAndLeftAsIsInText() {
        SubstitutionMail hostile = new SubstitutionMail(CHANGE, "NOTIFY", MONDAY, 3, null, null,
                "4КІ1", 0, "<script>alert(1)</script> & \"Q\"", null, null, "O'Neil <b>", null, "<img src=x>", null,
                null, false, false);
        Rendered letter = render(EmailKind.SUBSTITUTION_NOTICE, hostile);

        assertThat(letter.html()).doesNotContain("<script>", "<img src=x>", "<b>")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;Q&quot;")
                .contains("&lt;img src=x&gt;");
        assertThat(letter.text()).contains("Дисципліна: <script>alert(1)</script> & \"Q\"")
                .contains("Замість: O'Neil <b>")
                .doesNotContain("&lt;", "&amp;");
    }

    @Test
    void theGreetingNamesNobody() {
        // an account name is «Прізвище Ім’я По батькові» in the nominative, or «Адміністратор»: no use in a greeting
        assertThat(render(EmailKind.SUBSTITUTION_NOTICE, substitution("NOTIFY", "214", null)).text())
                .contains("\nДобрий день!\n");
    }

    @Test
    void thePreheaderIsPaddedSoThePreviewStopsThere() {
        String html = render(EmailKind.SUBSTITUTION_NOTICE, substitution("NOTIFY", "214", null)).html();
        assertThat(html).contains("<span>Комп’ютерні мережі, ауд. 214</span>&#8204;&#160;&#8204;&#160;");
        int preheader = html.indexOf("Комп’ютерні мережі, ауд. 214");
        assertThat(html.indexOf("Вам поставили заміну</h1>")).isGreaterThan(html.indexOf("</div>", preheader));
    }

    @Test
    void subjectHasNoLineBreaks() {
        SubstitutionMail mail = new SubstitutionMail(CHANGE, "NOTIFY", MONDAY, 3, null, null, "4КІ1\r\nBcc: x@y.z",
                0, null, null, null, null, null, null, null, null, false, false);
        Rendered letter = render(EmailKind.SUBSTITUTION_NOTICE, mail);
        assertThat(letter.subject()).doesNotContain("\r", "\n").endsWith("4КІ1 Bcc: x@y.z");
    }

    @Test
    void solvedGenerationListsThePenaltiesUnderTheirHeading() {
        Rendered letter = render(EmailKind.GENERATION_FINISHED, new GenerationMail(JOB, "SOLVED", 0, 35,
                List.of(new Penalty("Вікна в групі", 12), new Penalty("Пари в суботу", 3), new Penalty("Одна пара", 1)),
                null, false, "NO_IMPROVEMENT"));

        assertThat(letter.subject()).isEqualTo("Розклад згенеровано без конфліктів, штраф 35");
        assertThat(letter.text()).contains("Генерацію розкладу завершено\n\nДобрий день!\n\n"
                        + "Генератор склав розклад без конфліктів.\n"
                        + "Генерація зупинилась сама: розклад перестав покращуватися.\n\n"
                        + "Жорсткі порушення: 0\nШтраф м’яких обмежень: 35\n\n"
                        + "Найбільше порушень:\n"
                        + "• Вікна в групі: 12 випадків\n• Пари в суботу: 3 випадки\n• Одна пара: 1 випадок\n\n"
                        + MailTexts.REVIEW_RESULT + "\n")
                .contains("Відкрити генератор: https://rozklad.vtc.vn.ua/admin/generation?job=" + JOB)
                .endsWith("Налаштувати листи: https://rozklad.vtc.vn.ua/admin/notifications\n");
        // in HTML too the heading sits right above the list, after the score table
        String html = letter.html();
        int scores = html.indexOf("Штраф м’яких обмежень");
        int heading = html.indexOf(MailTexts.TOP_PENALTIES);
        int list = html.indexOf("<ul");
        assertThat(scores).isPositive().isLessThan(heading);
        assertThat(heading).isLessThan(list);
        assertThat(html.substring(heading, list)).doesNotContain("Жорсткі порушення", "<table");

        assertThat(render(EmailKind.GENERATION_FINISHED, solved(0, 0)).text()).doesNotContain(MailTexts.TOP_PENALTIES);
    }

    @Test
    void aTimetableWithHardViolationsIsReportedAsConflicts() {
        Rendered letter = render(EmailKind.GENERATION_FINISHED, new GenerationMail(JOB, "SOLVED", 2, 1240,
                List.of(new Penalty("Накладка викладача", 2)), null, false, "SAFETY_CAP"));

        assertThat(letter.subject()).isEqualTo("Розклад згенеровано з конфліктами: 2 жорсткі порушення");
        assertThat(letter.text()).startsWith("Розклад згенеровано з конфліктами\n")
                .contains("Генератор склав розклад, але в ньому лишилися конфлікти.")
                .contains("Жорсткі порушення — це накладки викладачів, груп чи аудиторій")
                .contains(MailTexts.REVIEW_CONFLICTS)
                .doesNotContain("Генератор склав розклад.", MailTexts.REVIEW_RESULT);
        assertThat(render(EmailKind.GENERATION_FINISHED, solved(1, 0)).subject())
                .isEqualTo("Розклад згенеровано з конфліктами: 1 жорстке порушення");
    }

    @Test
    void aStoppedGenerationSaysWhetherItsBestTimetableWasKept() {
        Rendered kept = render(EmailKind.GENERATION_FINISHED, new GenerationMail(JOB, "CANCELLED", 0, 80,
                List.of(new Penalty("Вікна в групі", 4)), null, true, "STOPPED"));
        assertThat(kept.subject()).isEqualTo("Генерацію зупинено: найкращий розклад збережено, штраф 80");
        assertThat(kept.text()).contains("Генерацію зупинили вручну; найкращий знайдений розклад збережено.")
                .contains("Жорсткі порушення: 0\nШтраф м’яких обмежень: 80\n")
                .contains("Найбільше порушень:\n• Вікна в групі: 4 випадки\n")
                .contains(MailTexts.REVIEW_RESULT);

        Rendered keptWithConflicts = render(EmailKind.GENERATION_FINISHED,
                new GenerationMail(JOB, "CANCELLED", 3, 80, List.of(), null, true, "STOPPED"));
        assertThat(keptWithConflicts.subject()).isEqualTo("Генерацію зупинено: у збереженому розкладі 3 жорсткі порушення");
        assertThat(keptWithConflicts.text()).contains(MailTexts.REVIEW_CONFLICTS);

        Rendered nothing = render(EmailKind.GENERATION_FINISHED,
                new GenerationMail(JOB, "CANCELLED", 0, 0, List.of(), null, false, null));
        assertThat(nothing.subject()).isEqualTo("Генерацію розкладу скасовано");
        assertThat(nothing.text()).contains("нічого не збережено").doesNotContain("Жорсткі порушення");
    }

    @Test
    void aFailedGenerationGivesTheReasonWithoutTheJavaClass() {
        Rendered failed = render(EmailKind.GENERATION_FINISHED, new GenerationMail(JOB, "FAILED", 0, 0, List.of(),
                "java.lang.IllegalStateException: No room of type LAB for 4КІ1", false, null));
        assertThat(failed.subject()).isEqualTo("Генерація розкладу не вдалася");
        assertThat(failed.text()).contains("Причина: No room of type LAB for 4КІ1\n").doesNotContain("java.lang");

        assertThat(MailTexts.failureReason("x".repeat(400))).isEqualTo("x".repeat(299) + "…");
        assertThat(MailTexts.failureReason("Розміщено 10 з 12 занять")).isEqualTo("Розміщено 10 з 12 занять");
        assertThat(MailTexts.failureReason("java.lang.OutOfMemoryError"))
                .isEqualTo("внутрішня помилка генератора (OutOfMemoryError).");
        assertThat(MailTexts.failureReason(" ")).isNull();
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
        assertThat(render(EmailKind.SUBSTITUTION_DECLINED, mail).subject())
                .isEqualTo("Відмова від заміни: Непийвода М.В., пн, 16 листопада, 3 пара, 4КІ1");
        assertThat(render(EmailKind.SUBSTITUTION_DECLINED, mail).text())
                .contains("Непийвода М.В. не може взяти 3 пару 4КІ1 у понеділок, 16 листопада.")
                .contains("Відкрити зміни: https://rozklad.vtc.vn.ua/admin/changes?date=2026-11-16");
        assertThat(render(EmailKind.SUBSTITUTION_OBJECTION, mail).text())
                .contains("а заміна вже в опублікованому розпорядженні")
                .contains("Позначте викладача відсутнім на цій парі");
        // many teachers are women: nothing in a dispatcher letter assumes the gender
        Rendered overdue = render(EmailKind.REQUEST_OVERDUE, mail);
        assertThat(overdue.subject()).isEqualTo("Немає відповіді на запит заміни: Непийвода М.В., пн, 16 листопада, "
                + "3 пара, 4КІ1");
        assertThat(overdue.text()).contains("Немає відповіді від Непийвода М.В. до пн, 16 листопада, 14:30.")
                .doesNotContain("відповів");
        assertThat(render(EmailKind.SUBSTITUTION_DECLINED, new AdminSubstitutionMail(CHANGE, null, null, null, null,
                null, null)).subject()).isEqualTo("Відмова від заміни: Викладач");
    }

    @Test
    void absenceNeverCarriesAReasonAndUnreachableListsPairs() {
        Rendered absence = render(EmailKind.ABSENCE_REPORTED, new AbsenceMail(UUID.randomUUID(), "Кренцін М.Д.",
                MONDAY, MONDAY.plusDays(2), "увесь день", "Telegram-бот"));
        assertThat(absence.subject()).isEqualTo("Відсутність: Кренцін М.Д., 16–18 листопада");
        assertThat(absence.text()).contains("Викладач: Кренцін М.Д.\nДати: 16–18 листопада\n"
                        + "Пари: увесь день\nДжерело: Telegram-бот\n")
                .doesNotContain("Причина", "відсутній");

        Rendered unreachable = render(EmailKind.TEACHER_UNREACHABLE, new UnreachableMail(UUID.randomUUID(),
                "Кренцін М.Д.", MONDAY, true, List.of("2 пара · 4КІ1 · КМ")));
        assertThat(unreachable.subject()).isEqualTo("Повідомте про заміну самі: Кренцін М.Д., пн, 16 листопада");
        assertThat(unreachable.text()).contains("Викладач: Кренцін М.Д.\nДата: Понеділок, 16 листопада\n")
                .contains("Пари:\n• 2 пара · 4КІ1 · КМ\n")
                .doesNotContain("підключив");
        assertThat(render(EmailKind.TEACHER_UNREACHABLE, new UnreachableMail(UUID.randomUUID(), "Кренцін М.Д.",
                MONDAY, false, List.of())).subject())
                .isEqualTo("Запит на заміну не надіслано: Кренцін М.Д., пн, 16 листопада");
    }

    @Test
    void orderRowsSecurityAndTestLetters() {
        UUID order = UUID.randomUUID();
        Rendered rows = render(EmailKind.ORDER_TEACHER, new OrderMail(order, 35, false, MONDAY, "ЗНАМЕННИК",
                List.of("🔁 4КІ1, 2 пара: КМ · Непийвода М.В., ауд. 6")));
        assertThat(rows.subject()).isEqualTo("Розпорядження №35 на 16 листопада: ваші зміни");
        assertThat(rows.text()).contains("Опубліковано розпорядження №35 на понеділок, 16 листопада (знаменник).")
                .contains("Рядки, що стосуються вас:\n• 🔁 4КІ1, 2 пара")
                .contains("PDF розпорядження: https://rozklad.vtc.vn.ua/api/documents/orders/" + order + ".pdf");

        Rendered relinked = render(EmailKind.SECURITY_TELEGRAM_RELINKED, new RelinkedMail("Непийвода М.В.",
                "Марія (@maria)", Instant.parse("2026-11-16T08:05:00Z")));
        assertThat(relinked.subject()).isEqualTo("Ваш Telegram у «Розклад ВТФК» підключено в іншому чаті");
        assertThat(relinked.text()).contains("16.11.2026 10:05 ваш Telegram у «Розклад ВТФК» підключили в іншому чаті "
                + "(обліковий запис Telegram: Марія (@maria))");

        Rendered test = render(EmailKind.TEST, new TestMail(Instant.now(), false));
        assertThat(test.subject()).isEqualTo("Тестовий лист від «Розклад ВТФК»");
        assertThat(test.text()).endsWith("Налаштувати листи: https://rozklad.vtc.vn.ua/me/notifications\n");
        // sent from the dispatcher's settings page: the footer leads back there
        assertThat(render(EmailKind.TEST, new TestMail(Instant.now(), true)).text())
                .endsWith("Налаштувати листи: https://rozklad.vtc.vn.ua/admin/notifications\n");
    }

    @Test
    void textPartIsTidy() {
        List<String> letters = List.of(
                render(EmailKind.SUBSTITUTION_NOTICE, substitution("NOTIFY", null, null)).text(),
                render(EmailKind.GENERATION_FINISHED, solved(2, 40)).text(),
                render(EmailKind.TEACHER_UNREACHABLE, new UnreachableMail(UUID.randomUUID(), "Кренцін М.Д.", MONDAY,
                        true, List.of("2 пара · 4КІ1 · КМ"))).text());
        assertThat(letters).allSatisfy(text ->
                assertThat(text).doesNotContain("\n\n\n", " \n", "[(", "th:").endsWith("\n"));
    }

    private Rendered render(EmailKind kind, MailModels.MailModel model) {
        return templates.render(texts.compose(kind, model));
    }

    private static GenerationMail solved(long hard, long soft) {
        return new GenerationMail(JOB, "SOLVED", hard, soft, hard > 0 ? List.of(new Penalty("Накладка аудиторії",
                (int) hard)) : List.of(), null, false, null);
    }

    private static SubstitutionMail substitution(String mode, String room, String whatChanged) {
        return new SubstitutionMail(CHANGE, mode, MONDAY, 3, LocalTime.of(11, 0), LocalTime.of(12, 0), "4КІ1", 1,
                "Комп’ютерні мережі", "REPLACE_TEACHER", null, "Кренцін М.Д.", null, room, DEADLINE, whatChanged,
                false, false);
    }
}
