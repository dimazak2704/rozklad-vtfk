package ua.vtc.rozklad.core.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Number of the first order of a semester published by the system. Used when the college started
 * the semester on paper: orders №1–34 exist only as documents, the system continues with №35.
 */
@Entity
@Table(name = "order_numbering")
@EntityListeners(AuditingEntityListener.class)
public class OrderNumbering {

    @Id
    @Column(name = "semester_id", nullable = false, updatable = false)
    private UUID semesterId;

    @Column(name = "first_number", nullable = false)
    private int firstNumber;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    protected OrderNumbering() {
    }

    public OrderNumbering(UUID semesterId, int firstNumber, String updatedBy) {
        this.semesterId = Objects.requireNonNull(semesterId, "semesterId");
        update(firstNumber, updatedBy);
    }

    public UUID getSemesterId() {
        return semesterId;
    }

    public int getFirstNumber() {
        return firstNumber;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void update(int firstNumber, String updatedBy) {
        if (firstNumber < 1) {
            throw new IllegalArgumentException("firstNumber must be positive");
        }
        this.firstNumber = firstNumber;
        this.updatedBy = updatedBy;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof OrderNumbering numbering && semesterId.equals(numbering.semesterId);
    }

    @Override
    public int hashCode() {
        return semesterId.hashCode();
    }
}
