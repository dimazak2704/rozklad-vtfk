package ua.vtc.rozklad.notification.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Commands, keyboard buttons and free text recognition. */
class BotParsingTest {

    @Test
    void commandsWithMentionAndArgument() {
        assertThat(Command.parse("/start K7QX-M2PA")).contains(new Command("start", "K7QX-M2PA", null));
        assertThat(Command.parse("  /Today@scheduler_vtfk_bot ")).contains(new Command("today", "", "scheduler_vtfk_bot"));
        assertThat(Command.parse("/bind   ABCD2345  ")).get().extracting(Command::argument).isEqualTo("ABCD2345");
        assertThat(Command.parse("4КІ1")).isEmpty();
        assertThat(Command.parse("/")).isEmpty();
        assertThat(Command.parse("/@bot")).isEmpty();
        assertThat(Command.parse(null)).isEmpty();

        Command other = Command.parse("/today@other_bot").orElseThrow();
        assertThat(other.addressedTo("scheduler_vtfk_bot")).isFalse();
        assertThat(Command.parse("/today@Scheduler_VTFK_bot").orElseThrow().addressedTo("scheduler_vtfk_bot")).isTrue();
        assertThat(Command.parse("/today").orElseThrow().addressedTo("scheduler_vtfk_bot")).isTrue();
    }

    @Test
    void keyboardButtonsAndTypedWordsMatchTheMenu() {
        for (MenuAction action : MenuAction.values()) {
            assertThat(MenuAction.match(action.label())).contains(action);
        }
        assertThat(MenuAction.match("сьогодні")).contains(MenuAction.TODAY);
        assertThat(MenuAction.match("Чисельник чи знаменник?")).contains(MenuAction.PARITY);
        assertThat(MenuAction.match("  ЗАВТРА!  ")).contains(MenuAction.TOMORROW);
        assertThat(MenuAction.match("Повідомити про відсутність")).contains(MenuAction.ABSENCE);
        assertThat(MenuAction.match("4КІ1")).isEqualTo(Optional.empty());
        assertThat(MenuAction.match("Кренцін")).isEmpty();
    }

    @Test
    void absenceLikeTextOfATeacherGoesToTheAi() {
        assertThat(AbsenceFlow.looksLikeAbsence("4.11 не буду, лікарняний")).isTrue();
        assertThat(AbsenceFlow.looksLikeAbsence("Завтра НЕ ЗМОЖУ на 1-2 пари")).isTrue();
        assertThat(AbsenceFlow.looksLikeAbsence("з 10 по 14 листопада відпустка")).isTrue();
        assertThat(AbsenceFlow.looksLikeAbsence("Кренцін")).isFalse();
        assertThat(AbsenceFlow.looksLikeAbsence("4КІ1")).isFalse();
    }
}
