package ua.vtc.rozklad.core.substitution.internal.change;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.text.UkrainianText;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.ChangeRepository;

/** Reads of changes for the dispatcher and for the assigned teacher. */
@Service
@Transactional(readOnly = true)
public class ChangeQueries {

    private final ChangeRepository changes;
    private final ChangeViews views;

    public ChangeQueries(ChangeRepository changes, ChangeViews views) {
        this.changes = changes;
        this.views = views;
    }

    /** Changes of a date (optionally one status) ordered like an order: course, group, pair. */
    public List<ChangeDto> forDate(LocalDate date, ChangeStatus status) {
        List<Change> found = changes.findAllByDateOrderByCreatedAt(date).stream()
                .filter(change -> status == null || change.getStatus() == status)
                .toList();
        Comparator<String> alphabetical = UkrainianText.collator();
        return views.toDtos(found).stream()
                .sorted(Comparator.comparingInt((ChangeDto dto) -> dto.group().course())
                        .thenComparing(dto -> dto.group().code(), alphabetical)
                        .thenComparingInt(ChangeDto::pairFrom)
                        .thenComparing(ChangeDto::createdAt))
                .toList();
    }

    public ChangeDto get(UUID id) {
        return views.toDto(changes.findById(id).orElseThrow(() -> NotFoundException.of("Зміну", id)));
    }

    /** «Мої заміни»: changes that assign the teacher, newest date first (drafts are never shown). */
    public List<ChangeDto> assignedTo(UUID teacherId, ChangeStatus status) {
        return views.toDtos(changes.findAllByNewTeacherIdOrderByDateDescPairFromAsc(teacherId).stream()
                .filter(change -> change.getStatus() != ChangeStatus.DRAFT)
                .filter(change -> status == null || change.getStatus() == status)
                .toList());
    }
}
