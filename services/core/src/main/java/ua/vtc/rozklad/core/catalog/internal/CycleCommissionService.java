package ua.vtc.rozklad.core.catalog.internal;

import static ua.vtc.rozklad.core.shared.persistence.EntityGuards.checkVersion;
import static ua.vtc.rozklad.core.shared.persistence.EntityGuards.deleteOrConflict;

import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.catalog.CatalogText;
import ua.vtc.rozklad.core.catalog.CycleCommission;
import ua.vtc.rozklad.core.catalog.CycleCommissionRepository;
import ua.vtc.rozklad.core.catalog.internal.StructureDtos.CycleCommissionDto;
import ua.vtc.rozklad.core.catalog.internal.StructureDtos.NameRequest;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.persistence.Specs;
import ua.vtc.rozklad.core.shared.web.PageQuery;
import ua.vtc.rozklad.core.shared.web.PageResponse;

/** Циклові комісії: CRUD with a unique name (case-insensitive). */
@Service
@Transactional(readOnly = true)
public class CycleCommissionService {

    private static final Map<String, String> SORTABLE = Map.of("name", "name");

    private final CycleCommissionRepository commissions;

    public CycleCommissionService(CycleCommissionRepository commissions) {
        this.commissions = commissions;
    }

    public PageResponse<CycleCommissionDto> list(String q, PageQuery page) {
        return PageResponse.of(commissions.findAll(Specs.containsIgnoreCase(q, "name"),
                page.toPageable(SORTABLE, Sort.by("name"))), CycleCommissionDto::of);
    }

    public CycleCommissionDto get(UUID id) {
        return CycleCommissionDto.of(find(id));
    }

    @Transactional
    public CycleCommissionDto create(NameRequest request) {
        String name = CatalogText.clean(request.name());
        ensureNameIsFree(name, null);
        return CycleCommissionDto.of(commissions.save(new CycleCommission(name)));
    }

    @Transactional
    public CycleCommissionDto update(UUID id, NameRequest request) {
        CycleCommission commission = find(id);
        checkVersion(commission, request.version());
        String name = CatalogText.clean(request.name());
        ensureNameIsFree(name, id);
        commission.setName(name);
        commissions.flush();
        return CycleCommissionDto.of(commission);
    }

    @Transactional
    public void delete(UUID id) {
        CycleCommission commission = find(id);
        deleteOrConflict(commissions, commission,
                () -> "Циклову комісію «%s» не можна видалити: до неї належать викладачі.".formatted(commission.getName()));
    }

    private CycleCommission find(UUID id) {
        return commissions.findById(id).orElseThrow(() -> NotFoundException.of("Циклову комісію", id));
    }

    private void ensureNameIsFree(String name, UUID ownId) {
        commissions.findByNameIgnoreCase(name)
                .filter(existing -> !existing.getId().equals(ownId))
                .ifPresent(existing -> {
                    throw new ConflictException("Циклова комісія «%s» уже існує.".formatted(existing.getName()));
                });
    }
}
