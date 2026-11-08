package ua.vtc.rozklad.core.catalog.internal;

import static ua.vtc.rozklad.core.shared.persistence.EntityGuards.checkVersion;
import static ua.vtc.rozklad.core.shared.persistence.EntityGuards.deleteOrConflict;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.catalog.Building;
import ua.vtc.rozklad.core.catalog.BuildingRepository;
import ua.vtc.rozklad.core.catalog.CatalogText;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.RoomRepository;
import ua.vtc.rozklad.core.catalog.internal.RoomDtos.RoomDto;
import ua.vtc.rozklad.core.catalog.internal.RoomDtos.RoomRequest;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.FieldValidationException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.persistence.Specs;
import ua.vtc.rozklad.core.shared.web.PageQuery;
import ua.vtc.rozklad.core.shared.web.PageResponse;
import ua.vtc.rozklad.core.shared.web.Ref;

/**
 * Аудиторії. The name is unique ignoring case and separators («С.З.» = «с.з.»): the Excel spells
 * the same room both ways.
 */
@Service
@Transactional(readOnly = true)
public class RoomService {

    private static final Map<String, String> SORTABLE = Map.of("name", "name", "type", "type",
            "capacity", "capacity", "active", "active");

    private final RoomRepository rooms;
    private final BuildingRepository buildings;

    public RoomService(RoomRepository rooms, BuildingRepository buildings) {
        this.rooms = rooms;
        this.buildings = buildings;
    }

    public PageResponse<RoomDto> list(String q, RoomType type, UUID buildingId, Boolean active, PageQuery page) {
        Page<Room> found = rooms.findAll(Specs.<Room>containsIgnoreCase(q, "name")
                        .and(Specs.equalTo("type", type))
                        .and(Specs.equalTo("buildingId", buildingId))
                        .and(Specs.equalTo("active", active)),
                page.toPageable(SORTABLE, Sort.by("name")));
        Map<UUID, Ref> buildingRefs = buildings.findAllById(found.getContent().stream()
                        .map(Room::getBuildingId).filter(Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Building::getId, b -> new Ref(b.getId(), b.getName())));
        return PageResponse.of(found, room -> RoomDto.of(room, buildingRefs.get(room.getBuildingId())));
    }

    public RoomDto get(UUID id) {
        return toDto(find(id));
    }

    @Transactional
    public RoomDto create(RoomRequest request) {
        String name = validName(request.name(), null);
        validateBuilding(request.buildingId());
        Room room = new Room(name, request.type());
        copy(request, room);
        rooms.save(room);
        return toDto(room);
    }

    @Transactional
    public RoomDto update(UUID id, RoomRequest request) {
        Room room = find(id);
        checkVersion(room, request.version());
        room.setName(validName(request.name(), id));
        validateBuilding(request.buildingId());
        room.setType(request.type());
        copy(request, room);
        rooms.flush();
        return toDto(room);
    }

    @Transactional
    public void delete(UUID id) {
        Room room = find(id);
        deleteOrConflict(rooms, room, () -> ("Аудиторію «%s» не можна видалити: вона є в розкладі, навантаженні "
                + "або закріплена за групою. Позначте її неактивною.").formatted(room.getName()));
    }

    Room find(UUID id) {
        return rooms.findById(id).orElseThrow(() -> NotFoundException.of("Аудиторію", id));
    }

    Optional<Room> findByNameKey(String key, UUID exceptId) {
        return rooms.findAll().stream()
                .filter(room -> !room.getId().equals(exceptId))
                .filter(room -> CatalogText.key(room.getName()).equals(key))
                .findFirst();
    }

    private RoomDto toDto(Room room) {
        Ref building = room.getBuildingId() == null ? null
                : buildings.findById(room.getBuildingId()).map(b -> new Ref(b.getId(), b.getName())).orElse(null);
        return RoomDto.of(room, building);
    }

    private String validName(String raw, UUID ownId) {
        String name = CatalogText.clean(raw);
        String key = CatalogText.key(name);
        if (key.isEmpty()) {
            throw FieldValidationException.of("name", "Назва аудиторії має містити літери або цифри.");
        }
        findByNameKey(key, ownId).ifPresent(existing -> {
            throw new ConflictException("Аудиторія «%s» уже існує.".formatted(existing.getName()));
        });
        return name;
    }

    private void validateBuilding(UUID buildingId) {
        if (buildingId != null && !buildings.existsById(buildingId)) {
            throw FieldValidationException.of("buildingId", "Корпус не знайдено.");
        }
    }

    private static void copy(RoomRequest request, Room room) {
        room.setBuildingId(request.buildingId());
        room.setCapacity(request.capacity());
        room.setMaxParallel(request.maxParallel() == null ? 1 : request.maxParallel());
        room.setActive(request.active() == null || request.active());
    }
}
