package ua.vtc.rozklad.scheduler.solver.domain;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Teacher (problem fact) with UNAVAILABLE (hard) and UNWANTED (soft) slots. Equality is by id. */
public final class Teacher {

    private final UUID id;
    private final String name;
    private final Set<Timeslot> unavailable;
    private final Set<Timeslot> unwanted;

    public Teacher(UUID id, String name, Set<Timeslot> unavailable, Set<Timeslot> unwanted) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = name;
        this.unavailable = Set.copyOf(unavailable);
        this.unwanted = Set.copyOf(unwanted);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Set<Timeslot> getUnavailable() {
        return unavailable;
    }

    public boolean isUnavailableAt(Timeslot timeslot) {
        return unavailable.contains(timeslot);
    }

    public boolean isUnwantedAt(Timeslot timeslot) {
        return unwanted.contains(timeslot);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Teacher teacher && id.equals(teacher.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}
