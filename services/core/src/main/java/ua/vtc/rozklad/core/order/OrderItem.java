package ua.vtc.rozklad.core.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.core.shared.domain.IdentifiableEntity;

/** Row of an order: text snapshot of a change as printed (DOMAIN §8). */
@Entity
@Table(name = "order_item")
public class OrderItem extends IdentifiableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private OrderDocument order;

    @Enumerated(EnumType.STRING)
    @Column(name = "section", nullable = false, length = 16)
    private OrderSection section;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "group_code", nullable = false, length = 16)
    private String groupCode;

    @Column(name = "pair_label", nullable = false, length = 16)
    private String pairLabel;

    @Column(name = "from_discipline", length = 300)
    private String fromDiscipline;

    @Column(name = "from_teacher", length = 200)
    private String fromTeacher;

    @Column(name = "reason_text", length = 100)
    private String reasonText;

    @Column(name = "to_discipline", length = 300)
    private String toDiscipline;

    @Column(name = "to_teacher", length = 200)
    private String toTeacher;

    @Column(name = "room_text", length = 100)
    private String roomText;

    @Enumerated(EnumType.STRING)
    @Column(name = "row_type", nullable = false, length = 16)
    private OrderRowType rowType;

    @Column(name = "change_id")
    private UUID changeId;

    protected OrderItem() {
    }

    OrderItem(OrderDocument order, OrderSection section, int position, OrderItemContent content) {
        this.order = Objects.requireNonNull(order, "order");
        this.section = Objects.requireNonNull(section, "section");
        this.position = position;
        this.groupCode = content.groupCode();
        this.pairLabel = content.pairLabel();
        this.rowType = content.rowType();
        this.fromDiscipline = content.fromDiscipline();
        this.fromTeacher = content.fromTeacher();
        this.reasonText = content.reasonText();
        this.toDiscipline = content.toDiscipline();
        this.toTeacher = content.toTeacher();
        this.roomText = content.roomText();
        this.changeId = content.changeId();
    }

    public OrderDocument getOrder() {
        return order;
    }

    public OrderSection getSection() {
        return section;
    }

    public int getPosition() {
        return position;
    }

    public String getGroupCode() {
        return groupCode;
    }

    public String getPairLabel() {
        return pairLabel;
    }

    public String getFromDiscipline() {
        return fromDiscipline;
    }

    public String getFromTeacher() {
        return fromTeacher;
    }

    public String getReasonText() {
        return reasonText;
    }

    public String getToDiscipline() {
        return toDiscipline;
    }

    public String getToTeacher() {
        return toTeacher;
    }

    public String getRoomText() {
        return roomText;
    }

    public OrderRowType getRowType() {
        return rowType;
    }

    public UUID getChangeId() {
        return changeId;
    }

    public OrderItemContent content() {
        return new OrderItemContent(groupCode, pairLabel, rowType, fromDiscipline, fromTeacher, reasonText,
                toDiscipline, toTeacher, roomText, changeId);
    }
}
