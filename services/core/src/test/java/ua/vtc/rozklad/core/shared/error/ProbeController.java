package ua.vtc.rozklad.core.shared.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.shared.security.CurrentUser;

/**
 * Test-only endpoints that raise every kind of error handled by {@link ApiExceptionHandler}.
 * The profile keeps it out of {@code @SpringBootTest} contexts that scan test classes.
 */
@RestController
@Profile(ProbeController.PROFILE)
class ProbeController {

    static final String PROFILE = "api-probe";

    static final UUID LESSON_ID = UUID.fromString("0190f0a0-0000-7000-8000-000000000001");

    record GroupForm(@NotBlank String code, @NotNull @Min(1) @Max(4) Integer course) {
    }

    record BellView(int pair, LocalTime startsAt, LocalTime endsAt) {
    }

    enum Category { SICK_LEAVE, OTHER }

    record AbsenceForm(LocalDate dateFrom, List<Integer> pairs, Category reasonCategory, String comment) {
    }

    record GridRow(@Min(1) @Max(5) int weekday, @Min(1) @Max(8) int pair) {
    }

    @PostMapping("/api/admin/probe/absences")
    AbsenceForm absence(@Valid @RequestBody AbsenceForm form) {
        return form;
    }

    @PutMapping("/api/admin/probe/grid")
    List<GridRow> grid(@RequestBody List<@Valid @NotNull GridRow> grid) {
        return grid;
    }

    @GetMapping("/api/admin/probe/days")
    LocalDate day(@RequestParam LocalDate from) {
        return from;
    }

    @PostMapping("/api/admin/probe/groups")
    GroupForm create(@Valid @RequestBody GroupForm form) {
        return form;
    }

    @GetMapping("/api/admin/probe/pairs")
    int pair(@RequestParam @Min(1) @Max(8) int pair) {
        return pair;
    }

    @GetMapping("/api/admin/probe/not-found")
    void notFound() {
        throw NotFoundException.of("Групу", "4КІ9");
    }

    @GetMapping("/api/admin/probe/conflict")
    void conflict() {
        throw ConflictException.schedule(List.of(ConflictDto.hard(ConflictKind.TEACHER,
                "Кренцін М.Д. уже веде пару в 4КІ2", List.of(LESSON_ID), LocalDate.of(2026, 10, 19), 2)));
    }

    @GetMapping("/api/admin/probe/business")
    void business() {
        throw new BusinessRuleException("ORDER_ALREADY_PUBLISHED", "Розпорядження вже опубліковане");
    }

    @GetMapping("/api/admin/probe/boom")
    void boom() {
        throw new IllegalStateException("secret internal detail");
    }

    @GetMapping("/api/me/probe")
    CurrentUser me(CurrentUser user) {
        return user;
    }

    @GetMapping("/api/public/probe/bell")
    BellView bell() {
        return new BellView(1, LocalTime.of(8, 30), LocalTime.of(9, 30));
    }
}
