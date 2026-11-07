package ua.vtc.rozklad.core.timetable.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.shared.security.CurrentUser;
import ua.vtc.rozklad.core.timetable.internal.metrics.MetricsDto;
import ua.vtc.rozklad.core.timetable.internal.publication.PublicationResult;
import ua.vtc.rozklad.core.timetable.internal.publication.PublicationService;
import ua.vtc.rozklad.core.timetable.internal.version.TimetableVersionDto;
import ua.vtc.rozklad.core.timetable.internal.version.TimetableVersionService;
import ua.vtc.rozklad.core.timetable.internal.version.VersionConflictsDto;
import ua.vtc.rozklad.core.timetable.internal.version.VersionDiffDto;

/** Versions of the base timetable (CONTRACTS.md §2): list, create/copy, analysis, diff, publication. */
@RestController
@RequestMapping("/api/admin/timetable/versions")
class TimetableVersionController {

    private final TimetableVersionService versions;
    private final PublicationService publication;

    TimetableVersionController(TimetableVersionService versions, PublicationService publication) {
        this.versions = versions;
        this.publication = publication;
    }

    /** Versions of the semester, newest first; without {@code semesterId} — of the active semester. */
    @GetMapping
    List<TimetableVersionDto> list(@RequestParam(required = false) UUID semesterId) {
        return versions.list(semesterId);
    }

    @PostMapping
    ResponseEntity<TimetableVersionDto> create(@Valid @RequestBody TimetableRequests.CreateVersion request) {
        TimetableVersionDto created = versions.create(request.semesterId(), request.name(),
                request.copyFromVersionId());
        return ResponseEntity.created(URI.create("/api/admin/timetable/versions/" + created.id())).body(created);
    }

    @GetMapping("/{versionId}")
    TimetableVersionDto get(@PathVariable UUID versionId) {
        return versions.get(versionId);
    }

    /** Deletes a draft (204); published and archived versions are kept (409). */
    @DeleteMapping("/{versionId}")
    ResponseEntity<Void> delete(@PathVariable UUID versionId) {
        versions.delete(versionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{versionId}/conflicts")
    VersionConflictsDto conflicts(@PathVariable UUID versionId) {
        return versions.conflicts(versionId);
    }

    @GetMapping("/{versionId}/metrics")
    MetricsDto metrics(@PathVariable UUID versionId) {
        return versions.metrics(versionId);
    }

    /** What changes when version {@code from} is replaced by version {@code to}. */
    @GetMapping("/{fromId}/diff/{toId}")
    VersionDiffDto diff(@PathVariable UUID fromId, @PathVariable UUID toId) {
        return versions.diff(fromId, toId);
    }

    /** 200 with the materialization summary, 409 with {@code conflicts[]} when the draft has hard conflicts. */
    @PostMapping("/{versionId}/publish")
    PublicationResult publish(@PathVariable UUID versionId, @Valid @RequestBody TimetableRequests.Publish request,
                              CurrentUser user) {
        return publication.publish(versionId, request.effectiveFrom(), user.username());
    }
}
