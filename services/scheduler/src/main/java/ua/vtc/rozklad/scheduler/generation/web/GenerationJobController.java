package ua.vtc.rozklad.scheduler.generation.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.contracts.GenerationStatus;
import ua.vtc.rozklad.scheduler.generation.GenerationJobRepository;
import ua.vtc.rozklad.scheduler.web.NotFoundException;

/** Read-only view of the scheduler for administrators (role ADMIN, see SecurityConfig). */
@RestController
@RequestMapping("/api/scheduler")
@Tag(name = "Генератор розкладу")
public class GenerationJobController {

    private final GenerationJobRepository repository;

    public GenerationJobController(GenerationJobRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/jobs/{id}")
    @Operation(summary = "Стан задачі генерації за ідентифікатором задачі core")
    public GenerationJobDto job(@PathVariable UUID id) {
        return repository.findById(id)
                .map(GenerationJobDto::of)
                .orElseThrow(() -> new NotFoundException("Задачу генерації " + id + " не знайдено."));
    }

    @GetMapping("/status")
    @Operation(summary = "Скільки задач чекає і скільки розв'язується зараз")
    public SchedulerStatusDto status() {
        return new SchedulerStatusDto(repository.countByStatus(GenerationStatus.QUEUED),
                repository.countByStatus(GenerationStatus.RUNNING));
    }
}
