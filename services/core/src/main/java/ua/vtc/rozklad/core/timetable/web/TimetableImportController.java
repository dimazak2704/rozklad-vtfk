package ua.vtc.rozklad.core.timetable.web;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.shared.error.FieldValidationException;
import ua.vtc.rozklad.core.timetable.internal.importing.ImportTimetableResult;
import ua.vtc.rozklad.core.timetable.internal.importing.TimetableImportService;

/**
 * {@code POST /api/admin/import/timetable} (ADMIN or the importer's SERVICE account): an imported
 * timetable becomes a draft version; 422 with {@code errors[]} lists names the catalog does not know.
 */
@RestController
@RequestMapping("/api/admin/import")
class TimetableImportController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final int MAX_KEY_LENGTH = 200;

    private final TimetableImportService imports;

    TimetableImportController(TimetableImportService imports) {
        this.imports = imports;
    }

    /** {@code Idempotency-Key} (optional, up to 200 characters): a repeat returns the first draft. */
    @PostMapping("/timetable")
    ResponseEntity<ImportTimetableResult> importTimetable(
            @Valid @RequestBody TimetableRequests.ImportTimetable request,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.strip();
        if (key != null && key.length() > MAX_KEY_LENGTH) {
            // перевірка вручну: обмеження на параметрі ввімкнуло б валідацію методу і змінило б шляхи полів тіла
            throw FieldValidationException.of(IDEMPOTENCY_KEY, "Не довше " + MAX_KEY_LENGTH + " символів.");
        }
        ImportTimetableResult result = imports.importTimetable(request.toCommand(), key);
        return ResponseEntity.created(URI.create("/api/admin/timetable/versions/" + result.versionId())).body(result);
    }
}
