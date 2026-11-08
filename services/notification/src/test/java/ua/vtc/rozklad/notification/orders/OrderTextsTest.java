package ua.vtc.rozklad.notification.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.notification.support.Order35;

class OrderTextsTest {

    @Test
    void groupMessageShowsWhatChangedCompactly() {
        String text = OrderTexts.forGroup(Order35.payload(), "4КІ1",
                OrderRows.forGroup(Order35.payload(), "4КІ1"));
        assertThat(text).isEqualTo("""
                📢 <b>4КІ1</b> · зміни на понеділок, 19 жовтня
                Розпорядження №35 · знаменник

                🔁 <b>2 пара</b>: <b>НДКС · Непийвода</b>, ауд. 6
                      замість <s>СТП · Кренцін</s> · взаємна заміна

                <i>Повний текст розпорядження — на зображенні.</i>""");
    }

    @Test
    void everyRowTypeHasItsOwnWording() {
        assertThat(OrderTexts.describe(Order35.row("1ТК1", "1-4", OrderRowType.BLOCK, "ЗУ", "Ємельяненко", null,
                null, null, "204", null), false)).isEqualTo("📌 <b>1–4 пари</b>: <b>ЗУ · Ємельяненко</b>, ауд. 204");
        assertThat(OrderTexts.describe(Order35.row("1ТК1", "1", OrderRowType.CANCELLATION, "Ін. мова",
                "Крисак Л. (п.)", "Відп.", null, null, null, null), false))
                .isEqualTo("❌ <b>1 пара</b>: пари не буде\n      <s>Ін. мова · Крисак Л. (п.)</s> · викладач відсутній");
        assertThat(OrderTexts.describe(Order35.row("1ОП2", "4", OrderRowType.ADDITIONAL, null, null, null, "БЖ",
                "Погосян", "403", null), true)).isEqualTo("➕ <b>1ОП2, 4 пара</b>: додатково <b>БЖ · Погосян</b>, ауд. 403");
        assertThat(OrderTexts.describe(Order35.row("2КІ1", "6", OrderRowType.FINISHED, "Іст.Укр.", "Романюк",
                "Вичит.", null, null, null, null), false))
                .isEqualTo("🏁 <b>6 пара</b>: пари не буде (вичитано)\n      <s>Іст.Укр. · Романюк</s>");
        assertThat(OrderTexts.describe(Order35.row("1ОБ", "4", OrderRowType.AS_SCHEDULED, null, null, null, null,
                null, null, null), false)).isEqualTo("↩️ <b>4 пара</b>: за розкладом");
        assertThat(OrderTexts.describe(Order35.row("1РТ1", "1", OrderRowType.ROOM_CHANGE, null, null, null, null,
                null, "401", null), false)).isEqualTo("🚪 <b>1 пара</b>: аудиторія <b>401</b>");
        assertThat(OrderTexts.describe(Order35.row("1КІ2", "2", OrderRowType.MERGE_SUBGROUPS, "Ін.мова",
                "Крисак Л. (п.)", "Відп.", "Ін.мова", "Можаровська", "12", null), false))
                .isEqualTo("👥 <b>2 пара</b>: обидві підгрупи разом — <b>Ін.мова · Можаровська</b>, ауд. 12"
                        + "\n      замість <s>Ін.мова · Крисак Л. (п.)</s> · викладач відсутній");
    }

    @Test
    void textsAreEscaped() {
        assertThat(OrderTexts.describe(Order35.row("1ТК1", "1", OrderRowType.REPLACEMENT, "A<b>", "B&C", null,
                "X", "Y", "1", null), false)).contains("A&lt;b&gt;", "B&amp;C").doesNotContain("<b>A");
    }

    @Test
    void adminSummaryCountsWhatWasSent() {
        String summary = OrderTexts.adminSummary(Order35.payload(), 2, 5, List.of("Ганза"), true, "");
        assertThat(summary).startsWith("📄 <b>Опубліковано розпорядження №35</b>\nПонеділок, 19 жовтня 2026 · знаменник")
                .contains("Змін: 16 · заміна аудиторій: 0 · приміток: 5")
                .contains("Надіслано: 2 чатам груп, 5 викладачам")
                .contains("⚠️ Без Telegram: Ганза")
                .doesNotContain("конвертер");
        assertThat(OrderTexts.adminSummary(Order35.payload(), 1, 1, List.of(), false, ""))
                .contains("1 чату групи, 1 викладачу")
                .contains("Зображення не надсилалося");
        assertThat(OrderTexts.adminSummary(Order35.payload(), 5, 21, List.of(), true, ""))
                .contains("Надіслано: 5 чатам груп, 21 викладачу");
        assertThat(OrderTexts.adminSummary(Order35.payload(), 21, 0, List.of(), true, ""))
                .contains("Надіслано: 21 чату групи, 0 викладачам");
    }
}
