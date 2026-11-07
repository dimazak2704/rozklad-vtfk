package ua.vtc.rozklad.core.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.core.shared.domain.BaseEntity;

/**
 * Викладач. {@code shortName} is the unique form used in schedules and orders («Кренцін М.Д.»),
 * {@code displayName} the compact one («Кренцін»). {@code keycloakUserId} links the account
 * created by invite; {@code needsReview} marks records created by import that an admin must check.
 */
@Entity
@Table(name = "teacher")
public class Teacher extends BaseEntity {

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "middle_name", length = 100)
    private String middleName;

    @Column(name = "short_name", nullable = false, length = 100)
    private String shortName;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "cycle_commission_id")
    private UUID cycleCommissionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment", nullable = false, length = 16)
    private Employment employment = Employment.STAFF;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "keycloak_user_id")
    private UUID keycloakUserId;

    @Column(name = "needs_review", nullable = false)
    private boolean needsReview;

    protected Teacher() {
    }

    public Teacher(String lastName, String shortName, String displayName) {
        setLastName(lastName);
        setShortName(shortName);
        setDisplayName(displayName);
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = Objects.requireNonNull(lastName, "lastName");
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getMiddleName() {
        return middleName;
    }

    public void setMiddleName(String middleName) {
        this.middleName = middleName;
    }

    public String getShortName() {
        return shortName;
    }

    public void setShortName(String shortName) {
        this.shortName = Objects.requireNonNull(shortName, "shortName");
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = Objects.requireNonNull(displayName, "displayName");
    }

    public UUID getCycleCommissionId() {
        return cycleCommissionId;
    }

    public void setCycleCommissionId(UUID cycleCommissionId) {
        this.cycleCommissionId = cycleCommissionId;
    }

    public Employment getEmployment() {
        return employment;
    }

    public void setEmployment(Employment employment) {
        this.employment = Objects.requireNonNull(employment, "employment");
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public UUID getKeycloakUserId() {
        return keycloakUserId;
    }

    public void setKeycloakUserId(UUID keycloakUserId) {
        this.keycloakUserId = keycloakUserId;
    }

    public boolean isNeedsReview() {
        return needsReview;
    }

    public void setNeedsReview(boolean needsReview) {
        this.needsReview = needsReview;
    }
}
