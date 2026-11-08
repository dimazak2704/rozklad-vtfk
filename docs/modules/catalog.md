# Module `catalog` (core)

Departments, specialties, cycle commissions, buildings, rooms, teachers (+aliases, competences),
disciplines (+aliases), groups (+subgroups); the public lists and the global fuzzy search; the
catalog import used by the importer.

## API for other modules (base package `ua.vtc.rozklad.core.catalog`)

| Type | What |
|---|---|
| `CatalogQueries` | batch refs (`groupRefs`, `teacherRefs`, `disciplineRefs`, `roomRefs`), read models (`group`, `groups`, `activeGroups`, `teacher`, `room`, `discipline`), tolerant natural-key lookups (`groupByCode("4ki1")`, `teacherByShortName("Кренцін М. Д.")` incl. aliases, `disciplineByShortName`, `roomByName("С.З.")`), `teacherByAccount(sub)`, competences (`hasCompetence`, `teachersCompetentIn`) |
| `GroupRef`, `TeacherRef`, `DisciplineRef`, `RoomRef` | the common DTOs of CONTRACTS.md |
| `GroupInfo`, `TeacherInfo`, `RoomInfo` | read models (`size`/`capacity` may be `null` = unknown) |
| `CatalogText` | DOMAIN §10 normalization: `normalize`, `key` (natural key without separators), `fromEnglishLayout`, `clean` |
| `GroupCodes` | canonical group codes («4ki1», «ІКІ1», «1ТК 1», «4КИ1» → 4КІ1 / 1КІ1 / 1ТК1 / 4КІ1), `parse`, `specialtyLetters` |
| `TeacherNames` | short / full name forms, surname of «ХарченкоГ.», «Овчар Ін.» |
| `UkrainianOrder.NATURAL` | display order (Ukrainian alphabet, numbers by value) |
| `CatalogLabels` | Ukrainian labels (room types, «ауд. 6») |
| `CatalogImported` | in-process event after an import commits (the demo seed listens to it) |
| `SubgroupUsage` (SPI) | modules that store subgroup numbers say whether a subgroup is used (load implements it) |

Entities and repositories stay in the base package (foundation rule).

## Endpoints

Admin (ADMIN): `/api/admin/departments`, `/specialties`, `/cycle-commissions`, `/buildings`, `/rooms`,
`/teachers` (+ `/{id}/aliases`, `/{id}/disciplines`), `/disciplines` (+ `/{id}/aliases`), `/groups`
(+ `/{id}/subgroups`). Lists: `?q=&page=0&size=20&sort=field,asc` → `{items,page,size,total}`;
sortable fields are whitelisted per resource (an unknown one is a 400). `POST` → 201 + `Location`,
`PUT` (optional `version` → 409 «Дані змінилися» when stale), `DELETE` → 204 or 409 when referenced.
Competences: `GET`, `PUT {disciplineIds}` (replace), `POST {disciplineId}`, `DELETE /{disciplineId}`.

Import (ADMIN or SERVICE): `POST /api/admin/import/catalog`.

Public: `GET /api/public/groups`, `/teachers`, `/rooms`, `/search?q=&limit=10` (limit 1..50).

## Decisions

- **Natural keys are compared normalized** (`CatalogText.key`): «Кренцін М. Д.» = «Кренцін М.Д.»,
  «С.З.» = «с.з.», «Укр.мова.» = «Укр.мова». A second spelling is a 409; it should become an alias.
  An alias may not equal another record's main short name (matching would be ambiguous).
- **Group codes**: course, specialty letters and number are derived from the canonical code and may
  not contradict it; the specialty must exist (the import creates a missing one, see below).
  Shift defaults to 2 for the 2nd course, 1 otherwise (DOMAIN §2 observation); send `shift` to override.
- **Specialty letters cannot change** while groups use them (they are part of the codes).
- **Delete = delete + flush**; a foreign key violation becomes 409 with a message naming the record
  (`EntityGuards.deleteOrConflict`). The database knows every reference, also from other modules.
  Subgroups have no FK (rows store numbers), so deletion asks the `SubgroupUsage` beans.
- **Search** mirrors `web/src/lib/search.ts`: query variants (as typed, group code with look-alikes
  and phonetic transliteration, English keyboard layout), tiers 100 exact key > 90 key prefix >
  80 word prefix > 75 whole number word («6» finds «ауд. 6», not «16») > 60 contains > 40 typo
  (Damerau–Levenshtein ≤1, ≤2 from 7 characters); then groups before teachers before rooms. It scans
  active groups/teachers/rooms per request (a few hundred rows) — no cache to invalidate.
- **Public teacher data**: short names only; full names (search and `fullName`) only with the setting
  `showTeacherFullNames`. Teacher aliases are searchable.
- **Import** (`CatalogImportRun`): one transaction, parents first (departments → specialties →
  commissions/buildings → teachers, disciplines, rooms → groups → aliases). Existing records get only
  the fields the item sends (merge), `needsReview` is set only on creation. A discipline without a
  full name keeps the abbreviation and `needsReview=true` (DOMAIN §10: never invent). A specialty that
  nobody sent is created with name = letters and reported in `warnings`. Commissions and buildings
  named by teachers/rooms are created implicitly. Any error → 400 with `errors[]` (`teachers[3].shortName`…)
  and nothing is stored. Response `ids` are keyed by the natural key exactly as sent.

## Import request

```json
{
  "departments": [{"name": "Комп’ютерне відділення", "shortName": "Комп’ютерне"}],
  "specialties": [{"letters": "КІ", "name": "Комп’ютерна інженерія", "code": "123", "departmentName": "Комп’ютерне відділення"}],
  "cycleCommissions": [{"name": "…"}], "buildings": [{"name": "…"}],
  "teachers": [{"shortName": "Кренцін М.Д.", "lastName": "Кренцін", "firstName": null, "middleName": null,
                "displayName": null, "cycleCommissionName": "…", "employment": "STAFF", "email": null, "needsReview": false}],
  "disciplines": [{"shortName": "СТП", "name": "Сучасні технології проєктування", "color": "#1F6FEB", "needsReview": false}],
  "rooms": [{"name": "с.з.", "type": "GYM", "capacity": null, "maxParallel": 3, "buildingName": null}],
  "groups": [{"code": "4КІ1", "course": 4, "specialtyLetters": "КІ", "size": 25, "shift": 1,
              "homeRoomName": "6", "curatorShortName": "Кренцін М.Д.", "subgroups": [1, 2]}],
  "aliases": [{"type": "TEACHER", "alias": "Граболінська О.", "target": "Гарболінська О."}]
}
```
→ `{created, updated, unchanged, ids: {groups: {"4КІ1": uuid}, teachers: {…}, …}, warnings: […]}`.

## How to test

`CatalogTextTest`, `GroupCodesTest`, `TeacherNamesTest`, `SearchMatcherTest`, `PublicCatalogSearchTest`
(ranking), `AdminGroupControllerTest`, `PublicCatalogControllerTest` (slices), `CatalogApiIT`
(PostgreSQL: CRUD, 409 on references and stale versions, aliases, import, search).

## Known gaps

- Admin list filter `q` is a case-insensitive `contains` in SQL (no look-alike tolerance); the
  fuzzy matching is the public search.
- Renaming a teacher's short name does not add the old spelling as an alias automatically.
