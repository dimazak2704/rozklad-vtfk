package ua.vtc.rozklad.core.catalog.web;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.catalog.internal.RoomDtos.RoomDto;
import ua.vtc.rozklad.core.catalog.internal.RoomDtos.RoomRequest;
import ua.vtc.rozklad.core.catalog.internal.RoomService;
import ua.vtc.rozklad.core.shared.web.Created;
import ua.vtc.rozklad.core.shared.web.PageQuery;
import ua.vtc.rozklad.core.shared.web.PageResponse;

/** {@code /api/admin/rooms}: list (filters {@code q, type, buildingId, active}; sort {@code name|type|capacity|active}) and CRUD. */
@RestController
@RequestMapping("/api/admin/rooms")
class AdminRoomController {

    private final RoomService rooms;

    AdminRoomController(RoomService rooms) {
        this.rooms = rooms;
    }

    @GetMapping
    PageResponse<RoomDto> list(@RequestParam(required = false) String q,
                               @RequestParam(required = false) RoomType type,
                               @RequestParam(required = false) UUID buildingId,
                               @RequestParam(required = false) Boolean active, PageQuery page) {
        return rooms.list(q, type, buildingId, active, page);
    }

    @GetMapping("/{id}")
    RoomDto get(@PathVariable UUID id) {
        return rooms.get(id);
    }

    @PostMapping
    ResponseEntity<RoomDto> create(@Valid @RequestBody RoomRequest request) {
        RoomDto created = rooms.create(request);
        return Created.at(created.id(), created);
    }

    @PutMapping("/{id}")
    RoomDto update(@PathVariable UUID id, @Valid @RequestBody RoomRequest request) {
        return rooms.update(id, request);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        rooms.delete(id);
        return ResponseEntity.noContent().build();
    }
}
