package ua.vtc.rozklad.notification.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.contracts.events.OrderPayload;
import ua.vtc.rozklad.contracts.events.OrderPayload.Item;
import ua.vtc.rozklad.notification.support.Order35;

class OrderRowsTest {

    @Test
    void groupGetsOnlyItsRowsIncludingRoomChanges() {
        OrderPayload order = withRoomChange(Order35.row("1ТК1", "3", OrderRowType.ROOM_CHANGE, null, null, null,
                null, null, "401", null));
        List<Item> rows = OrderRows.forGroup(order, "1ТК1");
        assertThat(rows).extracting(Item::pairLabel).containsExactly("1-4", "1", "3");
        assertThat(OrderRows.forGroup(order, "4КІ1")).extracting(Item::toTeacher).containsExactly("Непийвода");
        assertThat(OrderRows.forGroup(order, " 4кі1 ")).hasSize(1);
        assertThat(OrderRows.forGroup(order, "4КІ3")).isEmpty();
    }

    @Test
    void teacherIsFoundOnBothSidesOfTheReplacement() {
        assertThat(OrderRows.forTeacher(Order35.payload(), "Непийвода")).extracting(Item::groupCode)
                .containsExactly("4КІ1");
        assertThat(OrderRows.forTeacher(Order35.payload(), "Кренцін")).extracting(Item::groupCode)
                .containsExactly("4КІ1", "4КІ2");
    }

    @Test
    void initialsMustBeCompatibleAndSubgroupMarksAreIgnored() {
        // «Крисак Л. (п.)» і «Крисак Л.» — та сама людина
        assertThat(OrderRows.forTeacher(Order35.payload(), "Крисак Л.")).extracting(Item::groupCode)
                .containsExactly("1ТК1", "1КІ2", "1М", "3Ф");
        assertThat(OrderRows.forTeacher(Order35.payload(), "Непийвода М.В.")).hasSize(1);

        OrderPayload order = withItems(List.of(
                Order35.row("1М", "1", OrderRowType.REPLACEMENT, "ФВ", "Поліщук Л.", "Відп.", "ФВ", "Поліщук М.",
                        "с.з.", null),
                Order35.row("1Ф", "4", OrderRowType.REPLACEMENT, "Ін.мова", "Харченко О./Михальчук", "Зв.зам.",
                        "Інформ.", "Овчар Ін./Овчар Ів.", "304/309", null)));
        // DOMAIN §10: «Поліщук Л.» та «Поліщук М.», «Овчар Ін.» та «Овчар Ів.» — різні люди
        assertThat(OrderRows.forTeacher(order, "Поліщук М.")).hasSize(1);
        assertThat(OrderRows.forTeacher(order, "Поліщук Л.")).hasSize(1);
        assertThat(OrderRows.forTeacher(order, "Поліщук О.")).isEmpty();
        assertThat(OrderRows.forTeacher(order, "Овчар Ів.")).hasSize(1);
        assertThat(OrderRows.forTeacher(order, "Овчар Ін.")).hasSize(1);
        assertThat(OrderRows.forTeacher(order, "Михальчук")).hasSize(1);
        assertThat(OrderRows.forTeacher(order, "Овчаренко")).isEmpty();
    }

    @Test
    void namesAreParsedIntoSurnameAndInitials() {
        assertThat(OrderRows.Name.parse("Крисак Л. (п.)")).isEqualTo(new OrderRows.Name("крисак", "л"));
        assertThat(OrderRows.Name.parse("Непийвода М.В.")).isEqualTo(new OrderRows.Name("непийвода", "мв"));
        assertThat(OrderRows.Name.parse("  ")).isNull();
    }

    private static OrderPayload withRoomChange(Item roomChange) {
        OrderPayload base = Order35.payload();
        return new OrderPayload(base.id(), base.number(), base.date(), base.kind(), base.parentId(), base.status(),
                base.title(), base.weekdayLabel(), base.parityLabel(), base.items(), List.of(roomChange), base.notes(),
                base.signatoryPosition(), base.signatoryName(), base.semesterName(), base.affectedGroups(),
                base.affectedTeachers());
    }

    private static OrderPayload withItems(List<Item> items) {
        OrderPayload base = Order35.payload();
        return new OrderPayload(base.id(), base.number(), base.date(), base.kind(), base.parentId(), base.status(),
                base.title(), base.weekdayLabel(), base.parityLabel(), items, List.of(), base.notes(),
                base.signatoryPosition(), base.signatoryName(), base.semesterName(), base.affectedGroups(),
                base.affectedTeachers());
    }
}
