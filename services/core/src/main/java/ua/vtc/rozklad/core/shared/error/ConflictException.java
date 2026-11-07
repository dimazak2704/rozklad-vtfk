package ua.vtc.rozklad.core.shared.error;

import java.util.List;

/**
 * 409 Conflict. With {@code conflicts} it reports schedule conflicts ({@code conflicts[]} in the
 * ProblemDetail); without them it is a plain state conflict (duplicate username, entity still
 * referenced, change no longer pending, …). Messages are Ukrainian and shown to the user.
 */
public class ConflictException extends RuntimeException {

    private final transient List<ConflictDto> conflicts;

    public ConflictException(String message) {
        this(message, List.of());
    }

    public ConflictException(String message, List<ConflictDto> conflicts) {
        super(message);
        this.conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }

    public static ConflictException schedule(List<ConflictDto> conflicts) {
        return new ConflictException("Зміна створює конфлікти в розкладі", conflicts);
    }

    public List<ConflictDto> getConflicts() {
        return conflicts;
    }
}
