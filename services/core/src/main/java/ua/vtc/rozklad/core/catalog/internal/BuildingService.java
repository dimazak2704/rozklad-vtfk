package ua.vtc.rozklad.core.catalog.internal;

import static ua.vtc.rozklad.core.shared.persistence.EntityGuards.checkVersion;
import static ua.vtc.rozklad.core.shared.persistence.EntityGuards.deleteOrConflict;

import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.catalog.Building;
import ua.vtc.rozklad.core.catalog.BuildingRepository;
import ua.vtc.rozklad.core.catalog.CatalogText;
import ua.vtc.rozklad.core.catalog.internal.StructureDtos.BuildingDto;
import ua.vtc.rozklad.core.catalog.internal.StructureDtos.NameRequest;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.persistence.Specs;
import ua.vtc.rozklad.core.shared.web.PageQuery;
import ua.vtc.rozklad.core.shared.web.PageResponse;

/** Навчальні корпуси: CRUD with a unique name (case-insensitive). */
@Service
@Transactional(readOnly = true)
public class BuildingService {

    private static final Map<String, String> SORTABLE = Map.of("name", "name");

    private final BuildingRepository buildings;

    public BuildingService(BuildingRepository buildings) {
        this.buildings = buildings;
    }

    public PageResponse<BuildingDto> list(String q, PageQuery page) {
        return PageResponse.of(buildings.findAll(Specs.containsIgnoreCase(q, "name"),
                page.toPageable(SORTABLE, Sort.by("name"))), BuildingDto::of);
    }

    public BuildingDto get(UUID id) {
        return BuildingDto.of(find(id));
    }

    @Transactional
    public BuildingDto create(NameRequest request) {
        String name = CatalogText.clean(request.name());
        ensureNameIsFree(name, null);
        return BuildingDto.of(buildings.save(new Building(name)));
    }

    @Transactional
    public BuildingDto update(UUID id, NameRequest request) {
        Building building = find(id);
        checkVersion(building, request.version());
        String name = CatalogText.clean(request.name());
        ensureNameIsFree(name, id);
        building.setName(name);
        buildings.flush();
        return BuildingDto.of(building);
    }

    @Transactional
    public void delete(UUID id) {
        Building building = find(id);
        deleteOrConflict(buildings, building,
                () -> "Корпус «%s» не можна видалити: у ньому є аудиторії.".formatted(building.getName()));
    }

    private Building find(UUID id) {
        return buildings.findById(id).orElseThrow(() -> NotFoundException.of("Корпус", id));
    }

    private void ensureNameIsFree(String name, UUID ownId) {
        buildings.findByNameIgnoreCase(name)
                .filter(existing -> !existing.getId().equals(ownId))
                .ifPresent(existing -> {
                    throw new ConflictException("Корпус «%s» уже існує.".formatted(existing.getName()));
                });
    }
}
