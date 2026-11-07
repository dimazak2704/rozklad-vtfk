package ua.vtc.rozklad.core.calendar;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.core.shared.domain.IdentifiableEntity;

/** Group affected by a calendar period with scope GROUPS. */
@Entity
@Table(name = "calendar_period_group")
public class CalendarPeriodGroup extends IdentifiableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "period_id", nullable = false, updatable = false)
    private CalendarPeriod period;

    @Column(name = "group_id", nullable = false, updatable = false)
    private UUID groupId;

    protected CalendarPeriodGroup() {
    }

    CalendarPeriodGroup(CalendarPeriod period, UUID groupId) {
        this.period = Objects.requireNonNull(period, "period");
        this.groupId = Objects.requireNonNull(groupId, "groupId");
    }

    public CalendarPeriod getPeriod() {
        return period;
    }

    public UUID getGroupId() {
        return groupId;
    }
}
