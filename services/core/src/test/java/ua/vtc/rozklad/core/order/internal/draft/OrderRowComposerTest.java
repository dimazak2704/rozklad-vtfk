package ua.vtc.rozklad.core.order.internal.draft;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.core.order.OrderItemContent;

/** Rows of the real college orders №35, №96 and №105 (DOMAIN §8, §11). */
class OrderRowComposerTest {

    @Test
    void reproducesTheRowsOfOrder35() {
        List<ChangeRow> changes = List.of(
                row(ChangeKind.REPLACE_DISCIPLINE, "4КІ1", 0, 2, 2, "СТП", "Кренцін", "Зв.зам.", "НДКС", "Непийвода", "6"),
                row(ChangeKind.CANCEL, "1ТК1", 1, 1, 1, "Ін. мова", "Крисак Л.", "Відп.", null, null, null),
                row(ChangeKind.ADDITIONAL, "1ОП2", 0, 4, 4, null, null, null, "БЖ", "Погосян", "403"),
                row(ChangeKind.BLOCK, "1ТК1", 0, 1, 4, null, null, null, "ЗУ", "Ємельяненко", "204"),
                row(ChangeKind.REPLACE_TEACHER, "1М", 0, 3, 3, "Ін. мова", "Крисак Л.", "Відп.", "Ін. мова", "Прокопчук",
                        "110"),
                row(ChangeKind.REPLACE_TEACHER, "1КІ2", 0, 2, 2, "Ін. мова", "Крисак Л.", "Відп.", "Ін. мова",
                        "Прокопчук", "110"),
                row(ChangeKind.CANCEL, "3Ф", 1, 4, 4, "Ін. мова", "Крисак Л.", "Відп.", null, null, null),
                row(ChangeKind.REPLACE_DISCIPLINE, "4КІ2", 0, 4, 4, "СТП", "Кренцін", "Зв.зам.", "ОПЦЗ", "Ганза", "6"));

        OrderRowComposer.Rows rows = OrderRowComposer.compose(changes);

        assertThat(rows.changes()).extracting(OrderRowComposerTest::printed).containsExactly(
                "1КІ2 | 2 | REPLACEMENT | Ін. мова | Крисак Л. | Відп. | Ін. мова | Прокопчук | 110",
                "1М | 3 | REPLACEMENT | Ін. мова | Крисак Л. | Відп. | Ін. мова | Прокопчук | 110",
                "1ОП2 | 4 | ADDITIONAL | ДОДАТКОВО |  |  | БЖ | Погосян | 403",
                "1ТК1 | 1-4 | BLOCK | ЗУ | Ємельяненко |  |  |  | 204",
                "1ТК1 | 1 | CANCELLATION | Ін. мова | Крисак Л. (п.) | Відп. | Пари не буде |  | ",
                "3Ф | 4 | CANCELLATION | Ін. мова | Крисак Л. (п.) | Відп. | Пари не буде |  | ",
                "4КІ1 | 2 | REPLACEMENT | СТП | Кренцін | Зв.зам. | НДКС | Непийвода | 6",
                "4КІ2 | 4 | REPLACEMENT | СТП | Кренцін | Зв.зам. | ОПЦЗ | Ганза | 6");
        assertThat(rows.roomChanges()).isEmpty();
        assertThat(rows.changes()).allSatisfy(item -> assertThat(item.changeId()).isNotNull());
    }

    @Test
    void subgroupsOfOnePairMergeIntoOneRowAsInOrder105() {
        List<ChangeRow> changes = List.of(
                row(ChangeKind.REPLACE_DISCIPLINE, "1Ф", 2, 4, 4, "Ін.мова", "Михальчук", "Зв.зам.", "Інформ.",
                        "Овчар Ів.", "309"),
                row(ChangeKind.REPLACE_DISCIPLINE, "1Ф", 1, 4, 4, "Ін.мова", "Харченко О.", "Зв.зам.", "Інформ.",
                        "Овчар Ін.", "304"));

        assertThat(OrderRowComposer.compose(changes).changes()).extracting(OrderRowComposerTest::printed)
                .containsExactly("1Ф | 4 | REPLACEMENT | Ін.мова | Харченко О./Михальчук | Зв.зам. | Інформ. | "
                        + "Овчар Ін./Овчар Ів. | 304/309");
    }

    @Test
    void blockIsFollowedByThePairsThatStayAsScheduledAsInOrder105() {
        ChangeRow block = new ChangeRow(UUID.randomUUID(), ChangeKind.BLOCK, "1ОБ", 1, 0, 1, 3, null, null, null, "ЗУ",
                "Никитюк", "204", null, List.of(4));

        assertThat(OrderRowComposer.compose(List.of(block)).changes()).extracting(OrderRowComposerTest::printed)
                .containsExactly(
                        "1ОБ | 1-3 | BLOCK | ЗУ | Никитюк |  |  |  | 204",
                        "1ОБ | 4 | AS_SCHEDULED | За розкладом |  |  |  |  | ");
    }

    @Test
    void finishedDisciplineWithAndWithoutAReplacementAsInOrder96() {
        List<ChangeRow> changes = List.of(
                row(ChangeKind.DISCIPLINE_FINISHED, "2КІ1", 0, 6, 6, "Іст.Укр.", "Романюк", "Вичит.", null, null, "401"),
                row(ChangeKind.DISCIPLINE_FINISHED, "2КІ1", 0, 5, 5, "Укр.мова", "Давідчук", "Вичит.", "ТПМР", "Кренцін",
                        "6"));

        assertThat(OrderRowComposer.compose(changes).changes()).extracting(OrderRowComposerTest::printed)
                .containsExactly(
                        "2КІ1 | 5 | FINISHED | Укр.мова | Давідчук | Вичит. | ТПМР | Кренцін | 6",
                        "2КІ1 | 6 | FINISHED | Іст.Укр. | Романюк | Вичит. |  |  | ");
    }

    @Test
    void swapFillsTheSlotAndFreesThePairTheLessonLeaves() {
        ChangeRow swap = new ChangeRow(UUID.randomUUID(), ChangeKind.SWAP, "4КІ1", 4, 0, 2, 2, "СТП", "Кренцін",
                "Відп.", "НДКС", "Непийвода", "6", 4, List.of());

        assertThat(OrderRowComposer.compose(List.of(swap)).changes()).extracting(OrderRowComposerTest::printed)
                .containsExactly(
                        "4КІ1 | 2 | REPLACEMENT | СТП | Кренцін | Відп. | НДКС | Непийвода | 6",
                        "4КІ1 | 4 | CANCELLATION | НДКС | Непийвода |  | Пари не буде |  | ");
    }

    @Test
    void roomChangesGoToTheirOwnSection() {
        List<ChangeRow> changes = List.of(
                row(ChangeKind.ROOM_CHANGE, "1РТ2", 0, 3, 3, "Матем.", "Вихристюк", null, null, null, "403"),
                row(ChangeKind.ROOM_CHANGE, "1РТ1", 0, 1, 1, "БЖ", "Погосян", null, null, null, "401"));

        OrderRowComposer.Rows rows = OrderRowComposer.compose(changes);

        assertThat(rows.changes()).isEmpty();
        assertThat(rows.roomChanges()).extracting(OrderRowComposerTest::printed).containsExactly(
                "1РТ1 | 1 | ROOM_CHANGE |  |  |  |  |  | 401",
                "1РТ2 | 3 | ROOM_CHANGE |  |  |  |  |  | 403");
    }

    @Test
    void extraPairOfOneSubgroupMarksTheNewTeacher() {
        ChangeRow extra = row(ChangeKind.ADDITIONAL, "1ОП1", 1, 1, 1, null, null, null, "ФВ", "Остап'юк", "С.з.");

        OrderItemContent item = OrderRowComposer.compose(List.of(extra)).changes().getFirst();

        assertThat(item.rowType()).isEqualTo(OrderRowType.ADDITIONAL);
        assertThat(item.toTeacher()).isEqualTo("Остап'юк (п.)");
    }

    private static ChangeRow row(ChangeKind kind, String group, int subgroup, int pairFrom, int pairTo,
                                 String fromDiscipline, String fromTeacher, String reason, String toDiscipline,
                                 String toTeacher, String room) {
        int course = group.charAt(0) - '0';
        return new ChangeRow(UUID.randomUUID(), kind, group, course, subgroup, pairFrom, pairTo, fromDiscipline,
                fromTeacher, reason, toDiscipline, toTeacher, room, null, List.of());
    }

    /** One printed row: Група | Пара | type | За розкладом | Причина | По заміні | Ауд. */
    private static String printed(OrderItemContent item) {
        return String.join(" | ", item.groupCode(), item.pairLabel(), item.rowType().name(),
                text(item.fromDiscipline()), text(item.fromTeacher()), text(item.reasonText()),
                text(item.toDiscipline()), text(item.toTeacher()), text(item.roomText()));
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
