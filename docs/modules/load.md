# Module `load` (core)

Навантаження: load items group × discipline × semester with hours per week, planned hours, teachers
per subgroup, weekly pattern; derivation from a timetable version; import by natural keys.

## API for other modules (base package `ua.vtc.rozklad.core.load`)

| Type | What |
|---|---|
| `LoadQueries` | `semesterItems`, `groupItems`, `item` (`LoadItemView`), `weeklyUnits(semesterId)` (units for generation), `pattern(hours)`, `teachesInSemester(teacher, discipline, semester)` (candidate ranking) |
| `LoadPattern` | DOMAIN §4: `p = hours / hoursPerPair`, whole = ⌊p⌋, remainder ≥ 0.25 → one HALF, ≥ 0.75 → one more whole (3 h → 1+½, 4 h → 2, 1 h → ½) |
| `WeeklyUnit` | `unitId = <loadItemId>:<subgroup>:<index>`, WHOLE/HALF, teacher of that subgroup, room requirements |

## Endpoints

- `/api/admin/load?semesterId=&groupId=` (semesterId required) → `[LoadItemDto]` sorted by group and
  discipline; `GET/POST/PUT/DELETE /{id}`. `LoadItemDto` = CONTRACTS fields + `streamId`, `note`, `version`.
- `POST /api/admin/load/derive-from-version/{versionId}` → `{versionId, semesterId, created, updated,
  removed, skipped:[{group, discipline, reason}], items:[LoadItemDto]}`.
- `POST /api/admin/import/load` (ADMIN or SERVICE) `{semesterId, items:[{groupCode, disciplineShortName,
  hoursPerWeek, plannedHours?, lessonKind?, splitSubgroups?, teachers:[{subgroup, teacherShortName}],
  controlForm?, requiredRoomType?, preferredRoomName?, source, note?}]}` → `{created, updated, unchanged,
  ids: {"4КІ1 | СТП | MIXED | whole": uuid}}`.

## Decisions

- One item per group × discipline × lesson kind × split (409 on a duplicate).
- Import without `splitSubgroups` (curriculum, program) updates the item of group × discipline × lesson
  kind that is there and keeps its split and subgroup teachers; with none it creates a whole-group item;
  with both a whole-group and a split item it is refused (400 `items[i].splitSubgroups`), nothing written.
- Hours per week 0.1..40 with one decimal; planned hours default to `hoursPerWeek × teachingWeeks`
  of the semester (rounded).
- Whole group: at most a teacher for subgroup 0. Split: the group must have subgroups in the catalog;
  teachers only for existing subgroups 1..n. Weekly units of a split item are created for every
  catalog subgroup of the group (a subgroup without a teacher gets `teacherId = null`).
- Editing an item makes it `MANUAL`, so re-derivation never overwrites the admin's numbers. Deleting
  an item keeps template lessons (`load_item_id` → NULL).
- **Derivation** (`LoadDerivation`): BOTH = 1 pair a week, NUMERATOR/DENOMINATOR = ½; whole-group lessons
  give one item, subgroup lessons a second split item (each subgroup gets as many pairs as the busiest);
  teacher = the one with most pairs (ties → smallest id); kind = the shared kind, else MIXED. Derived
  items of the semester are upserted by group × discipline × split; derived items the version no longer
  implies are removed; a group × discipline with load from another source is skipped and reported.
- **Template lessons are read with SQL** (`TemplateLessonReader`), not through the timetable module: the
  timetable module depends on load (generation), so a Java dependency back would be a module cycle.
- `LoadSubgroupUsage` implements the catalog SPI: a subgroup with a load teacher cannot be deleted.

## How to test

`LoadPatternTest`, `LoadDerivationTest`, `LoadApiIT` (CRUD and pattern, split validation, weekly units,
subgroup guard, derivation incl. skip and idempotency, import).
