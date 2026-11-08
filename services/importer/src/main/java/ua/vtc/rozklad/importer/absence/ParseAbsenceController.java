package ua.vtc.rozklad.importer.absence;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.importer.web.SupportedDate;

/** CONTRACTS §5 {@code POST /api/import/ai/parse-absence} (ADMIN or SERVICE — the Telegram bot). */
@RestController
@Tag(name = "ШІ", description = "Облік витрат на розпізнавання")
public class ParseAbsenceController {

    /**
     * {@code teacherId} is accepted for the contract but not needed to read the text; {@code today}
     * lies in 2000–2100 like every date core accepts.
     */
    public record ParseAbsenceRequest(@NotBlank @Size(max = 1000) String text, UUID teacherId,
                                      @NotNull @SupportedDate LocalDate today) {
    }

    private final ParseAbsenceService service;

    public ParseAbsenceController(ParseAbsenceService service) {
        this.service = service;
    }

    @Operation(summary = "Розібрати повідомлення викладача про відсутність (правила, за потреби — ШІ)")
    @PostMapping("/api/import/ai/parse-absence")
    public ParsedAbsence parse(@Valid @RequestBody ParseAbsenceRequest request) {
        return service.parse(request.text(), request.today());
    }
}
