package ua.vtc.rozklad.core.substitution.internal.change;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.catalog.DisciplineRef;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.catalog.RoomRef;
import ua.vtc.rozklad.core.catalog.TeacherRef;
import ua.vtc.rozklad.core.shared.domain.ReasonCode;
import ua.vtc.rozklad.core.substitution.ApprovalMode;

/** Sample {@link ChangeDto} for web slice tests: 4КІ1, 2 пара, СТП Кренцін → Непийвода. */
final class ChangeDtos {

    static final UUID ID = UUID.fromString("0190f0a0-0000-7000-8000-0000000000c1");

    private ChangeDtos() {
    }

    static ChangeDto sample(ChangeStatus status) {
        RoomRef room = new RoomRef(UUID.randomUUID(), "6", RoomType.COMPUTER_LAB);
        DisciplineRef stp = new DisciplineRef(UUID.randomUUID(), "СТП", "Сучасні технології проєктування", null);
        return new ChangeDto(ID, ChangeKind.REPLACE_TEACHER, status, LocalDate.of(2026, 10, 19), 2, 2,
                new GroupRef(UUID.randomUUID(), "4КІ1", 4), 0,
                new ChangeDto.Parties(stp, new TeacherRef(UUID.randomUUID(), "Кренцін М.Д.", "Кренцін"), room),
                new ChangeDto.Parties(stp, new TeacherRef(UUID.randomUUID(), "Непийвода М.В.", "Непийвода"), room),
                ReasonCode.ABSENT, null, null, Instant.parse("2026-10-18T16:00:00Z"), null, null,
                List.of(new ChangeDto.HistoryEntry(Instant.parse("2026-10-18T15:00:00Z"), "admin", null, status,
                        "Створено")),
                ApprovalMode.CONFIRM, UUID.randomUUID(), null, null, "Відп.", null, null, "admin",
                Instant.parse("2026-10-18T15:00:00Z"), 0);
    }
}
