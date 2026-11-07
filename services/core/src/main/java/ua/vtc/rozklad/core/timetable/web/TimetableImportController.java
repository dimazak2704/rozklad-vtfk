package ua.vtc.rozklad.core.timetable.web;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.timetable.internal.importing.ImportTimetableResult;
import ua.vtc.rozklad.core.timetable.internal.importing.TimetableImportService;

/**
 * {@code POST /api/admin/import/timetable} (ADMIN or the importer's SERVICE account): an imported
 * timetable becomes a draft version; 422 with {@code errors[]} lists names the catalog does not know.
 */
@RestController
@RequestMapping("/api/admin/import")
class TimetableImportController {

    private final TimetableImportService imports;

    TimetableImportController(TimetableImportService imports) {
        this.imports = imports;
    }

    @PostMapping("/timetable")
    ResponseEntity<ImportTimetableResult> importTimetable(@Valid @RequestBody TimetableRequests.ImportTimetable request) {
        ImportTimetableResult result = imports.importTimetable(request.toCommand());
        return ResponseEntity.created(URI.create("/api/admin/timetable/versions/" + result.versionId())).body(result);
    }
}
