package ua.vtc.rozklad.core.catalog.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.catalog.internal.PublicCatalogDtos.PublicGroupDto;
import ua.vtc.rozklad.core.catalog.internal.PublicCatalogDtos.PublicRoomDto;
import ua.vtc.rozklad.core.catalog.internal.PublicCatalogDtos.PublicTeacherDto;
import ua.vtc.rozklad.core.catalog.internal.PublicCatalogDtos.SearchResultDto;
import ua.vtc.rozklad.core.catalog.internal.PublicCatalogService;

/** Anonymous catalog endpoints of CONTRACTS §1: groups, teachers, rooms and the global search. */
@RestController
@RequestMapping("/api/public")
class PublicCatalogController {

    private final PublicCatalogService catalog;

    PublicCatalogController(PublicCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/groups")
    List<PublicGroupDto> groups() {
        return catalog.groups();
    }

    @GetMapping("/teachers")
    List<PublicTeacherDto> teachers() {
        return catalog.teachers();
    }

    @GetMapping("/rooms")
    List<PublicRoomDto> rooms() {
        return catalog.rooms();
    }

    /** Fuzzy search: Latin/Cyrillic look-alikes, keyboard layout and typos (DOMAIN §10). */
    @GetMapping("/search")
    List<SearchResultDto> search(@RequestParam(defaultValue = "") @Size(max = 100) String q,
                                 @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        return catalog.search(q, limit);
    }
}
