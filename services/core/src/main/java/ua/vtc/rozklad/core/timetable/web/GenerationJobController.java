package ua.vtc.rozklad.core.timetable.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.shared.security.CurrentUser;
import ua.vtc.rozklad.core.timetable.internal.generation.GenerationJobDto;
import ua.vtc.rozklad.core.timetable.internal.generation.GenerationService;

/** Timetable generation jobs (CONTRACTS.md §2): start, list, read, cancel. */
@RestController
@RequestMapping("/api/admin/generation/jobs")
class GenerationJobController {

    private final GenerationService generation;

    GenerationJobController(GenerationService generation) {
        this.generation = generation;
    }

    /** 202 {@code {jobId}}: the scheduler works asynchronously; progress comes over SSE. */
    @PostMapping
    ResponseEntity<JobCreated> start(@Valid @RequestBody GenerationRequests.StartGeneration request,
                                     CurrentUser user) {
        GenerationJobDto job = generation.start(request.toCommand(), user.username());
        return ResponseEntity.accepted().location(URI.create("/api/admin/generation/jobs/" + job.id()))
                .body(new JobCreated(job.id()));
    }

    /** Jobs of the semester, newest first; without {@code semesterId} the latest 50 of all semesters. */
    @GetMapping
    List<GenerationJobDto> list(@RequestParam(required = false) UUID semesterId) {
        return generation.list(semesterId);
    }

    @GetMapping("/{jobId}")
    GenerationJobDto get(@PathVariable UUID jobId) {
        return generation.get(jobId);
    }

    /** 202 with the cancelled job; 409 when the job has already finished. */
    @PostMapping("/{jobId}/cancel")
    ResponseEntity<GenerationJobDto> cancel(@PathVariable UUID jobId) {
        return ResponseEntity.accepted().body(generation.cancel(jobId));
    }

    record JobCreated(UUID jobId) {
    }
}
