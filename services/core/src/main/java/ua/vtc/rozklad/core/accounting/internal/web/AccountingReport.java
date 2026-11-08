package ua.vtc.rozklad.core.accounting.internal.web;

import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.accounting.TeacherActivity;
import ua.vtc.rozklad.core.accounting.TeacherActivityProvider;
import ua.vtc.rozklad.core.accounting.internal.AccountingRow;
import ua.vtc.rozklad.core.accounting.internal.AccountingService;
import ua.vtc.rozklad.core.accounting.internal.LedgerResult;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.DisciplineRef;
import ua.vtc.rozklad.core.catalog.DisciplineRepository;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.StudentGroupRepository;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherRef;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.shared.text.UkrainianText;

/** Builds the accounting responses (references resolved in batches, stable ordering). */
@Service
@Transactional(readOnly = true)
class AccountingReport {

    private final AccountingService accounting;
    private final TeacherActivityProvider teacherActivity;
    private final StudentGroupRepository groups;
    private final DisciplineRepository disciplines;
    private final TeacherRepository teachers;

    AccountingReport(AccountingService accounting, TeacherActivityProvider teacherActivity,
                     StudentGroupRepository groups, DisciplineRepository disciplines, TeacherRepository teachers) {
        this.accounting = accounting;
        this.teacherActivity = teacherActivity;
        this.groups = groups;
        this.disciplines = disciplines;
        this.teachers = teachers;
    }

    List<AccountingRowDto> rows(UUID semesterId, UUID groupId) {
        List<AccountingRow> rows = accounting.rows(semesterId, groupId);
        Map<UUID, StudentGroup> groupsById = byId(groups.findAllById(
                rows.stream().map(AccountingRow::groupId).distinct().toList()), StudentGroup::getId);
        Map<UUID, Discipline> disciplinesById = byId(disciplines.findAllById(
                rows.stream().map(AccountingRow::disciplineId).distinct().toList()), Discipline::getId);
        Comparator<String> alphabetical = UkrainianText.collator();
        return rows.stream()
                .map(row -> toDto(row, groupsById.get(row.groupId()), disciplinesById.get(row.disciplineId())))
                .sorted(Comparator.comparingInt((AccountingRowDto dto) -> dto.group().course())
                        .thenComparing(dto -> dto.group().code(), alphabetical)
                        .thenComparing(dto -> dto.discipline().shortName(), alphabetical))
                .toList();
    }

    List<TeacherStatsDto> teachers(YearMonth month) {
        List<TeacherActivity> activity = teacherActivity.activityBetween(month.atDay(1), month.atEndOfMonth());
        Map<UUID, Teacher> teachersById = byId(teachers.findAllById(
                activity.stream().map(TeacherActivity::teacherId).toList()), Teacher::getId);
        int hoursPerPair = accounting.hoursPerPair();
        Comparator<String> alphabetical = UkrainianText.collator();
        return activity.stream()
                .map(row -> {
                    Teacher teacher = teachersById.get(row.teacherId());
                    return new TeacherStatsDto(
                            new TeacherRef(teacher.getId(), teacher.getShortName(), teacher.getDisplayName()),
                            row.substitutions(), row.substitutedPairs() * hoursPerPair, row.missedPairs());
                })
                .sorted(Comparator.comparingInt(TeacherStatsDto::substitutionsCount).reversed()
                        .thenComparing(dto -> dto.teacher().shortName(), alphabetical))
                .toList();
    }

    private static AccountingRowDto toDto(AccountingRow row, StudentGroup group, Discipline discipline) {
        LedgerResult ledger = row.ledger();
        LedgerResult.Suggestion suggestion = ledger.suggestion();
        return new AccountingRowDto(
                new GroupRef(group.getId(), group.getCode(), group.getCourse()),
                new DisciplineRef(discipline.getId(), discipline.getShortName(), discipline.getName(),
                        discipline.getColor()),
                ledger.plan(), ledger.fact(), ledger.remaining(), ledger.forecast(), ledger.status(),
                ledger.forecastFinishDate(),
                suggestion == null ? null : new AccountingRowDto.SuggestionDto(suggestion.kind().name(),
                        suggestion.text(), suggestion.fromDate(), suggestion.lessons(), suggestion.lessonIds()));
    }

    private static <T> Map<UUID, T> byId(List<T> entities, Function<T, UUID> id) {
        return entities.stream().collect(Collectors.toMap(id, Function.identity()));
    }
}
