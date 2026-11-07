package ua.vtc.rozklad.core.order;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.OrderStatus;
import ua.vtc.rozklad.core.shared.domain.BaseEntity;

/**
 * Розпорядження for a date (table {@code order_document}: {@code order} is reserved in SQL).
 * {@code number} is assigned on publication, sequential per semester; an AMENDMENT has a
 * {@code parentId}. Items are a denormalized text snapshot (the document of record).
 */
@Entity
@Table(name = "order_document")
public class OrderDocument extends BaseEntity {

    @Column(name = "semester_id", nullable = false, updatable = false)
    private UUID semesterId;

    @Column(name = "number")
    private Integer number;

    @Column(name = "date", nullable = false, updatable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16, updatable = false)
    private OrderKind kind;

    @Column(name = "parent_id", updatable = false)
    private UUID parentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OrderStatus status = OrderStatus.DRAFT;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "weekday_label", length = 20)
    private String weekdayLabel;

    @Column(name = "parity_label", length = 20)
    private String parityLabel;

    @Column(name = "signatory_position", length = 200)
    private String signatoryPosition;

    @Column(name = "signatory_name", length = 200)
    private String signatoryName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "notes", nullable = false, columnDefinition = "jsonb")
    private List<String> notes = new ArrayList<>();

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by", length = 100)
    private String publishedBy;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("section ASC, position ASC")
    private List<OrderItem> items = new ArrayList<>();

    protected OrderDocument() {
    }

    private OrderDocument(UUID semesterId, LocalDate date, OrderKind kind, UUID parentId, String title) {
        this.semesterId = Objects.requireNonNull(semesterId, "semesterId");
        this.date = Objects.requireNonNull(date, "date");
        this.kind = kind;
        this.parentId = parentId;
        setTitle(title);
    }

    public static OrderDocument mainOrder(UUID semesterId, LocalDate date, String title) {
        return new OrderDocument(semesterId, date, OrderKind.ORDER, null, title);
    }

    public static OrderDocument amendment(UUID semesterId, LocalDate date, UUID parentId, String title) {
        return new OrderDocument(semesterId, date, OrderKind.AMENDMENT, Objects.requireNonNull(parentId, "parentId"),
                title);
    }

    public UUID getSemesterId() {
        return semesterId;
    }

    public Integer getNumber() {
        return number;
    }

    public LocalDate getDate() {
        return date;
    }

    public OrderKind getKind() {
        return kind;
    }

    public UUID getParentId() {
        return parentId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public boolean isDraft() {
        return status == OrderStatus.DRAFT;
    }

    /** DRAFT → PUBLISHED with the next number of the semester (numbering lives in the service). */
    public void markPublished(int number, Instant publishedAt, String publishedBy) {
        if (status != OrderStatus.DRAFT) {
            throw new IllegalStateException("Order is already published");
        }
        if (number < 1) {
            throw new IllegalArgumentException("number must be positive");
        }
        this.number = number;
        this.publishedAt = Objects.requireNonNull(publishedAt, "publishedAt");
        this.publishedBy = publishedBy;
        this.status = OrderStatus.PUBLISHED;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = Objects.requireNonNull(title, "title");
    }

    public String getWeekdayLabel() {
        return weekdayLabel;
    }

    public String getParityLabel() {
        return parityLabel;
    }

    /** E.g. «ПОНЕДІЛОК» and «ЗНАМЕННИК». */
    public void setDayLabels(String weekdayLabel, String parityLabel) {
        this.weekdayLabel = weekdayLabel;
        this.parityLabel = parityLabel;
    }

    public String getSignatoryPosition() {
        return signatoryPosition;
    }

    public String getSignatoryName() {
        return signatoryName;
    }

    public void setSignatory(String position, String name) {
        this.signatoryPosition = position;
        this.signatoryName = name;
    }

    public List<String> getNotes() {
        return List.copyOf(notes);
    }

    public void setNotes(List<String> notes) {
        this.notes = new ArrayList<>(notes == null ? List.of() : notes);
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getPublishedBy() {
        return publishedBy;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public List<OrderItem> getItems(OrderSection section) {
        return items.stream().filter(item -> item.getSection() == section).toList();
    }

    public OrderItem addItem(OrderSection section, OrderItemContent content) {
        int position = (int) items.stream().filter(item -> item.getSection() == section).count();
        OrderItem item = new OrderItem(this, section, position, content);
        items.add(item);
        return item;
    }

    /** Removes all rows of a draft before it is rebuilt. */
    public void clearItems() {
        if (!isDraft()) {
            throw new IllegalStateException("Items of a published order cannot be changed");
        }
        items.clear();
    }
}
