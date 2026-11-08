package ua.vtc.rozklad.notification.bot;

import static ua.vtc.rozklad.notification.text.Html.b;
import static ua.vtc.rozklad.notification.text.Html.code;

import java.util.List;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.ReplyKeyboard;

/** Fixed bot texts (Ukrainian, short, buttons first) and the main keyboard. */
public final class BotTexts {

    public static final String CORE_DOWN = "😕 Не вдалося отримати дані розкладу — сервер тимчасово недоступний. "
            + "Спробуйте за хвилину.";
    public static final String STALE_BUTTON = "Ця кнопка вже не діє — почніть, будь ласка, спочатку.";
    public static final String SOMETHING_WRONG = "😕 Щось пішло не так. Спробуйте ще раз трохи згодом.";
    public static final String CHOOSE_GROUP_FIRST = "Спершу оберіть свою групу 👇";
    public static final String TEACHERS_ONLY = "Ця дія доступна викладачам, які підключили Telegram у кабінеті "
            + "викладача. Попросіть диспетчера надіслати посилання для підключення.";

    private static final String PLACEHOLDER = "Група, викладач або аудиторія…";

    private BotTexts() {
    }

    public static ReplyKeyboard mainMenu(boolean teacher) {
        List<List<String>> rows = new java.util.ArrayList<>(List.of(
                List.of(MenuAction.TODAY.label(), MenuAction.TOMORROW.label()),
                List.of(MenuAction.MY_GROUP.label(), MenuAction.FIND.label()),
                List.of(MenuAction.BELLS.label(), MenuAction.PARITY.label())));
        if (teacher) {
            rows.add(List.of(MenuAction.TEACHER_TOMORROW.label(), MenuAction.ABSENCE.label()));
        }
        return new ReplyKeyboard(rows, PLACEHOLDER);
    }

    public static String greeting(String groupCode, String teacherName) {
        if (teacherName != null) {
            return "Вітаю, " + b(teacherName) + "! 👋\n"
                    + "Тут ваші пари, прохання про заміни й повідомлення про відсутність.\n"
                    + "«🧑‍🏫 Мої пари завтра» — розклад на завтра, «🤒 Відсутність» — повідомити диспетчера.";
        }
        if (groupCode != null) {
            return "Привіт! 👋 Ваша група — " + b(groupCode) + ".\n"
                    + "Натисніть «📅 Сьогодні» або «📆 Завтра» — покажу пари з усіма замінами.";
        }
        return "Привіт! 👋 Я бот розкладу ВТФК.\n"
                + "Покажу пари на сьогодні й завтра з усіма замінами, розклад дзвінків і чи зараз чисельник.\n\n"
                + "Спершу оберіть свою групу — натисніть «👥 Моя група».";
    }

    public static String help(boolean teacher, String botUsername) {
        StringBuilder text = new StringBuilder("ℹ️ ").append(b("Що я вмію")).append('\n')
                .append("📅 Сьогодні / 📆 Завтра — розклад вашої групи із замінами\n")
                .append("👥 Моя група — обрати групу й ранкову розсилку о 7:30\n")
                .append("🔎 Знайти — розклад будь-якої групи, викладача чи аудиторії\n")
                .append("🔔 Дзвінки — розклад дзвінків і скільки триває пара\n")
                .append("🔢 Чисельник? — який зараз тиждень");
        if (teacher) {
            text.append("\n🧑‍🏫 Мої пари завтра — ваш розклад на завтра")
                    .append("\n🤒 Відсутність — повідомити диспетчера; можна й текстом: ")
                    .append("<i>«4.11 не буду, лікарняний»</i>");
        }
        text.append("\n\nУ будь-якому чаті напишіть ").append(code("@" + botUsername + " 4КІ1"))
                .append(" — і поділіться розкладом групи.");
        return text.toString();
    }

    public static String groupHelp() {
        return "ℹ️ " + b("Команди для чату групи") + "\n"
                + "/today — розклад групи на сьогодні\n"
                + "/tomorrow — на завтра\n"
                + "/status — що отримує цей чат\n"
                + "/rozklad_on, /rozklad_off — увімкнути чи вимкнути ранковий розклад о 7:30\n"
                + "/bind КОД — прив’язати чат до групи (код дає диспетчер у веб-панелі)\n\n"
                + "Заміни після публікації розпорядження приходять завжди. "
                + "Ранковий розклад вмикає адміністратор чату — спершу він вимкнений.";
    }
}
