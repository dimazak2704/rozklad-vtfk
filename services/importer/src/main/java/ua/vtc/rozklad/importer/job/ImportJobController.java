package ua.vtc.rozklad.importer.job;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import ua.vtc.rozklad.importer.normalize.EntityType;
import ua.vtc.rozklad.importer.normalize.MappingDecision;
import ua.vtc.rozklad.importer.web.InvalidRequestException;
import ua.vtc.rozklad.importer.web.NulFreeStringDeserializer;
import ua.vtc.rozklad.importer.web.PageResponse;

/** CONTRACTS §5: upload, list, details, mappings and apply of import jobs (role ADMIN). */
@RestController
@RequestMapping("/api/import/jobs")
@Validated
@Tag(name = "Імпорт", description = "Завантаження файлів, перевірка зіставлень і застосування")
public class ImportJobController {

    private final ImportJobService service;

    public ImportJobController(ImportJobService service) {
        this.service = service;
    }

    @Operation(summary = "Завантажити файл (≤ 20 МБ) і поставити його в чергу на розбір")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportJobDto> upload(@RequestParam("file") MultipartFile file,
                                               @RequestParam("kind") ImportKind kind,
                                               @RequestParam(value = "semesterId", required = false) UUID semesterId,
                                               @RequestParam(value = "options", required = false) String options,
                                               @AuthenticationPrincipal Jwt jwt) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw InvalidRequestException.field("file", "Не вдалося прочитати завантажений файл.");
        }
        ImportJob job = service.upload(new ImportJobService.Upload(kind, file.getOriginalFilename(), file.getContentType(),
                content, semesterId, options, actor(jwt)));
        return ResponseEntity.accepted().location(URI.create("/api/import/jobs/" + job.id())).body(ImportJobDto.of(job));
    }

    @Operation(summary = "Список завдань імпорту (нові спершу), без чернеток і звітів")
    @GetMapping
    public PageResponse<ImportJobDto> list(@RequestParam(required = false) ImportKind kind,
                                           @RequestParam(required = false) ImportJobStatus status,
                                           @RequestParam(defaultValue = "0") @Min(0) int page,
                                           @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.of(service.list(kind, status, pageable), ImportJobDto::of);
    }

    @Operation(summary = "Завдання імпорту зі звітом і чернеткою")
    @GetMapping("/{id}")
    public ImportJobDto get(@PathVariable UUID id) {
        return ImportJobDto.of(service.get(id));
    }

    public record MappingsRequest(@NotNull @Size(max = 500) List<@NotNull @Valid MappingItem> mappings) {
    }

    /**
     * {@code targetId} — map to an existing entity; {@code createNew} — create a new one (optional
     * field overrides); neither — remove the earlier decision for this value.
     */
    public record MappingItem(@NotNull EntityType type, @NotBlank @Size(max = 300) String raw, UUID targetId,
                              JsonNode createNew, boolean addAlias) {
    }

    @Operation(summary = "Зберегти рішення щодо зіставлень і перерахувати звіт")
    @PutMapping("/{id}/mappings")
    public ImportJobDto updateMappings(@PathVariable UUID id, @Valid @RequestBody MappingsRequest request) {
        List<Map.Entry<String, String>> errors = new ArrayList<>();
        List<ImportJobService.MappingChange> changes = new ArrayList<>();
        for (int i = 0; i < request.mappings().size(); i++) {
            MappingItem item = request.mappings().get(i);
            String prefix = "mappings[" + i + "]";
            boolean hasCreate = item.createNew() != null && !item.createNew().isNull();
            if (item.targetId() != null && hasCreate) {
                errors.add(Map.entry(prefix, "Вкажіть або targetId (наявний запис), або createNew (новий запис), але не обидва."));
            }
            if (hasCreate && !item.createNew().isObject()) {
                errors.add(Map.entry(prefix + ".createNew", "createNew має бути JSON-об'єктом (можна порожнім)."));
            }
            // createNew — дерево JSON, а не рядки DTO: NUL перевіряємо тут, як NulFreeStringDeserializer
            if (hasCreate && item.createNew().isObject()) {
                for (Map.Entry<String, JsonNode> field : item.createNew().properties()) {
                    if (field.getValue().isString() && field.getValue().asString().indexOf('\0') >= 0) {
                        errors.add(Map.entry(prefix + ".createNew." + field.getKey(), NulFreeStringDeserializer.MESSAGE));
                    }
                }
            }
            if (item.addAlias() && item.targetId() == null) {
                errors.add(Map.entry(prefix + ".addAlias", "Псевдонім додається лише при зіставленні з наявним записом."));
            }
            if (item.addAlias() && !item.type().supportsAliases()) {
                errors.add(Map.entry(prefix + ".addAlias", "Псевдоніми підтримуються лише для викладачів і дисциплін."));
            }
            boolean reset = item.targetId() == null && !hasCreate;
            changes.add(new ImportJobService.MappingChange(i, new MappingDecision(item.type(), item.raw().strip(),
                    item.targetId(), hasCreate ? item.createNew() : null, item.addAlias()), reset));
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }
        return ImportJobDto.of(service.updateMappings(id, changes));
    }

    /** Optional body: the semester to apply into (overrides the one given at upload). */
    public record ApplyRequest(UUID semesterId) {
    }

    @Operation(summary = "Застосувати імпорт через core (асинхронно; повторний виклик нічого не дублює)")
    @PostMapping("/{id}/apply")
    public ResponseEntity<ImportJobDto> apply(@PathVariable UUID id, @RequestBody(required = false) ApplyRequest request) {
        ImportJob job = service.requestApply(id, request == null ? null : request.semesterId());
        return ResponseEntity.accepted().body(ImportJobDto.of(job));
    }

    private static String actor(Jwt jwt) {
        if (jwt == null) {
            return "unknown";
        }
        String username = jwt.getClaimAsString("preferred_username");
        return username != null && !username.isBlank() ? username : jwt.getSubject();
    }
}
