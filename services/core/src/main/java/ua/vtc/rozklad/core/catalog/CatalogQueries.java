package ua.vtc.rozklad.core.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Read API of the catalog for other modules: references for DTOs (batch lookups avoid N+1),
 * read models for rules, tolerant lookups by natural keys for imports, teacher competences.
 * Lookups by natural key apply DOMAIN §10 normalization and aliases. Batch maps are read-only and
 * answer {@code null} for an unknown or {@code null} id (nullable references are common).
 */
public interface CatalogQueries {

    Map<UUID, GroupRef> groupRefs(Collection<UUID> ids);

    Map<UUID, TeacherRef> teacherRefs(Collection<UUID> ids);

    Map<UUID, DisciplineRef> disciplineRefs(Collection<UUID> ids);

    Map<UUID, RoomRef> roomRefs(Collection<UUID> ids);

    Optional<GroupInfo> group(UUID id);

    Map<UUID, GroupInfo> groups(Collection<UUID> ids);

    /** Active groups ordered by course and code. */
    List<GroupInfo> activeGroups();

    Optional<TeacherInfo> teacher(UUID id);

    Optional<RoomInfo> room(UUID id);

    Optional<DisciplineRef> discipline(UUID id);

    /** «4ki1», «4КИ1», «4 КІ 1» all find «4КІ1». */
    Optional<GroupInfo> groupByCode(String code);

    /** Short name («Кренцін М. Д.» = «Кренцін М.Д.») or a teacher alias. */
    Optional<TeacherInfo> teacherByShortName(String shortName);

    /** Short name («Укр.мова.» = «Укр.мова») or a discipline alias. */
    Optional<DisciplineRef> disciplineByShortName(String shortName);

    /** Room name, case and separators ignored («С.З.» = «с.з.»). */
    Optional<RoomInfo> roomByName(String name);

    /** Teacher linked to a Keycloak account ({@code sub} of the access token). */
    Optional<TeacherInfo> teacherByAccount(UUID keycloakUserId);

    /** Competence used by candidate ranking (DOMAIN §7): {@code teacher_discipline}. */
    boolean hasCompetence(UUID teacherId, UUID disciplineId);

    Set<UUID> teachersCompetentIn(UUID disciplineId);
}
