package ua.vtc.rozklad.core.catalog.internal;

import static ua.vtc.rozklad.core.shared.persistence.EntityGuards.checkVersion;
import static ua.vtc.rozklad.core.shared.persistence.EntityGuards.deleteOrConflict;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.catalog.CatalogText;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.DisciplineAliasRepository;
import ua.vtc.rozklad.core.catalog.DisciplineRepository;
import ua.vtc.rozklad.core.catalog.internal.DisciplineDtos.DisciplineDto;
import ua.vtc.rozklad.core.catalog.internal.DisciplineDtos.DisciplineRequest;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.FieldValidationException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.persistence.Specs;
import ua.vtc.rozklad.core.shared.web.PageQuery;
import ua.vtc.rozklad.core.shared.web.PageResponse;

/**
 * Дисципліни. The short name («СТП») is the natural key of imports, unique by its normalized key
 * («Укр.мова.» = «Укр.мова»). When the full name is unknown the importer keeps the abbreviation
 * and sets {@code needsReview} (DOMAIN §10: never invent names).
 */
@Service
@Transactional(readOnly = true)
public class DisciplineService {

    private static final Map<String, String> SORTABLE = Map.of("name", "name", "shortName", "shortName",
            "active", "active", "needsReview", "needsReview");

    private final DisciplineRepository disciplines;
    private final DisciplineAliasRepository aliases;

    public DisciplineService(DisciplineRepository disciplines, DisciplineAliasRepository aliases) {
        this.disciplines = disciplines;
        this.aliases = aliases;
    }

    public PageResponse<DisciplineDto> list(String q, Boolean active, Boolean needsReview, PageQuery page) {
        return PageResponse.of(disciplines.findAll(
                Specs.<Discipline>containsIgnoreCase(q, "name", "shortName")
                        .and(Specs.equalTo("active", active))
                        .and(Specs.equalTo("needsReview", needsReview)),
                page.toPageable(SORTABLE, Sort.by("shortName"))), DisciplineDto::of);
    }

    public DisciplineDto get(UUID id) {
        return DisciplineDto.of(find(id));
    }

    @Transactional
    public DisciplineDto create(DisciplineRequest request) {
        String shortName = validShortName(request.shortName(), null);
        Discipline discipline = new Discipline(CatalogText.clean(request.name()), shortName);
        copy(request, discipline);
        return DisciplineDto.of(disciplines.save(discipline));
    }

    @Transactional
    public DisciplineDto update(UUID id, DisciplineRequest request) {
        Discipline discipline = find(id);
        checkVersion(discipline, request.version());
        discipline.setShortName(validShortName(request.shortName(), id));
        discipline.setName(CatalogText.clean(request.name()));
        copy(request, discipline);
        disciplines.flush();
        return DisciplineDto.of(discipline);
    }

    @Transactional
    public void delete(UUID id) {
        Discipline discipline = find(id);
        deleteOrConflict(disciplines, discipline, () -> ("Дисципліну «%s» не можна видалити: вона є в розкладі, "
                + "навантаженні або замінах. Позначте її неактивною.").formatted(discipline.getShortName()));
    }

    public Discipline find(UUID id) {
        return disciplines.findById(id).orElseThrow(() -> NotFoundException.of("Дисципліну", id));
    }

    Optional<Discipline> findByShortNameKey(String key, UUID exceptId) {
        return disciplines.findAll().stream()
                .filter(discipline -> !discipline.getId().equals(exceptId))
                .filter(discipline -> CatalogText.key(discipline.getShortName()).equals(key))
                .findFirst();
    }

    private String validShortName(String raw, UUID ownId) {
        String shortName = CatalogText.clean(raw);
        String key = CatalogText.key(shortName);
        if (key.isEmpty()) {
            throw FieldValidationException.of("shortName", "Скорочена назва має містити літери або цифри.");
        }
        findByShortNameKey(key, ownId).ifPresent(existing -> {
            throw new ConflictException("Дисципліна «%s» уже існує.".formatted(existing.getShortName()));
        });
        aliases.findByNormalized(key)
                .filter(alias -> !alias.getDisciplineId().equals(ownId))
                .ifPresent(alias -> {
                    throw new ConflictException("Назва «%s» уже записана як псевдонім іншої дисципліни.".formatted(shortName));
                });
        return shortName;
    }

    private static void copy(DisciplineRequest request, Discipline discipline) {
        String color = DepartmentService.blankToNull(request.color());
        discipline.setColor(color == null ? null : color.toUpperCase(Locale.ROOT));
        discipline.setActive(request.active() == null || request.active());
        discipline.setNeedsReview(request.needsReview() != null && request.needsReview());
    }
}
