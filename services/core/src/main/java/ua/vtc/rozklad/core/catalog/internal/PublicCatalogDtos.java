package ua.vtc.rozklad.core.catalog.internal;

import java.util.UUID;
import ua.vtc.rozklad.contracts.RoomType;

/** Public (anonymous) catalog DTOs of CONTRACTS §1. */
public final class PublicCatalogDtos {

    private PublicCatalogDtos() {
    }

    /** {@code department} is the department name or {@code null} when the specialty has none. */
    public record PublicGroupDto(UUID id, String code, int course, SpecialtyView specialty, String department,
                                 int shift) {
    }

    public record SpecialtyView(String letters, String name) {
    }

    /**
     * {@code fullName} is filled only when the setting {@code showTeacherFullNames} allows it and
     * the first name is known; otherwise public pages show short names only.
     */
    public record PublicTeacherDto(UUID id, String shortName, String displayName, String cycleCommission,
                                   String fullName) {
    }

    public record PublicRoomDto(UUID id, String name, RoomType type, Integer capacity, String building) {
    }

    /** {@code url} is the SPA route of the schedule: {@code /g/{code}}, {@code /t/{id}}, {@code /r/{id}}. */
    public record SearchResultDto(SearchResultType type, UUID id, String label, String sublabel, String url) {
    }

    public enum SearchResultType {
        GROUP,
        TEACHER,
        ROOM
    }
}
