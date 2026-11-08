package ua.vtc.rozklad.core.order.internal.dashboard;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.OrderStatus;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.calendar.SemesterInfo;
import ua.vtc.rozklad.core.order.OrderDocument;
import ua.vtc.rozklad.core.order.OrderDocumentRepository;
import ua.vtc.rozklad.core.order.internal.support.OrderCalendar;
import ua.vtc.rozklad.core.shared.text.UkrainianText;
import ua.vtc.rozklad.core.substitution.SubstitutionDaySummary;
import ua.vtc.rozklad.core.substitution.SubstitutionOverview;
import ua.vtc.rozklad.core.timetable.GenerationJobRepository;

/**
 * Dispatcher dashboard of a date: substitution figures, the order draft, the latest generation
 * job and alerts with links into the SPA. Lives in the order module because it reads both
 * substitutions and orders (substitution must not depend on orders).
 */
@Service
@Transactional(readOnly = true)
class Dashboard {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final SubstitutionOverview substitutions;
    private final OrderDocumentRepository orders;
    private final GenerationJobRepository generationJobs;
    private final OrderCalendar calendar;

    Dashboard(SubstitutionOverview substitutions, OrderDocumentRepository orders,
              GenerationJobRepository generationJobs, OrderCalendar calendar) {
        this.substitutions = substitutions;
        this.orders = orders;
        this.generationJobs = generationJobs;
        this.calendar = calendar;
    }

    DashboardDto of(LocalDate date) {
        Optional<SemesterInfo> semester = calendar.findSemester(date);
        Parity parity = calendar.parityOrNull(date);
        SubstitutionDaySummary summary = substitutions.summarize(date);
        Optional<OrderDocument> draft = orders.findAllByDateAndStatusOrderByCreatedAt(date, OrderStatus.DRAFT).stream()
                .reduce((first, second) -> second);
        DashboardDto.Generation generation = semester
                .flatMap(found -> generationJobs.findAllBySemesterIdOrderByCreatedAtDesc(found.id()).stream()
                        .findFirst())
                .map(job -> new DashboardDto.Generation(job.getStatus()))
                .orElse(null);
        return new DashboardDto(date, parity, summary.absences(), summary.uncoveredLessons(), summary.pending(),
                summary.confirmed(), summary.declined(),
                draft.map(order -> new DashboardDto.OrderDraft(order.getId(), order.getItems().size())).orElse(null),
                generation, null, alerts(date, semester.isPresent(), summary));
    }

    private static List<DashboardDto.Alert> alerts(LocalDate date, boolean inSemester, SubstitutionDaySummary summary) {
        String changesLink = "/admin/changes?date=" + date;
        List<DashboardDto.Alert> alerts = new ArrayList<>();
        if (!inSemester) {
            alerts.add(new DashboardDto.Alert("NO_SEMESTER", DashboardDto.Severity.WARN,
                    "Дата %s не входить у жоден семестр.".formatted(date.format(DATE)), "/admin/calendar"));
        }
        summary.declinedChanges().forEach(declined -> alerts.add(new DashboardDto.Alert("DECLINED",
                DashboardDto.Severity.ERROR, "%s відмовився від заміни: %s, %s пара".formatted(
                        declined.teacherName() == null ? "Викладач" : declined.teacherName(), declined.groupCode(),
                        declined.pairFrom() == declined.pairTo() ? String.valueOf(declined.pairFrom())
                                : declined.pairFrom() + "-" + declined.pairTo()),
                changesLink)));
        if (summary.uncoveredLessons() > 0) {
            int count = summary.uncoveredLessons();
            alerts.add(new DashboardDto.Alert("UNCOVERED_LESSONS", DashboardDto.Severity.WARN,
                    "%d %s без рішення через відсутність викладачів".formatted(count,
                            UkrainianText.plural(count, "пара", "пари", "пар")), changesLink));
        }
        if (summary.overduePending() > 0) {
            int count = summary.overduePending();
            alerts.add(new DashboardDto.Alert("PENDING_OVERDUE", DashboardDto.Severity.WARN,
                    "%d %s довше встановленого часу чекають підтвердження".formatted(count,
                            UkrainianText.plural(count, "заміна", "заміни", "замін")), changesLink));
        }
        if (summary.confirmed() > 0) {
            int count = summary.confirmed();
            alerts.add(new DashboardDto.Alert("ORDER_NOT_PUBLISHED", DashboardDto.Severity.INFO,
                    "%d %s ще не опубліковано в розпорядженні".formatted(count,
                            UkrainianText.plural(count, "підтверджену зміну", "підтверджені зміни",
                                    "підтверджених змін")), "/admin/orders?date=" + date));
        }
        return alerts;
    }
}
