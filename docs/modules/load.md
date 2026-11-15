# Module `load` (core)

Навантаження: load items group × discipline × semester with hours per week, planned hours (counted over the group's
theory days unless a number was set), teachers per subgroup, weekly pattern; derivation from a timetable version; import
by natural keys.

## API for other modules (base package `ua.vtc.rozklad.core.load`)

| Type | What |
|---|---|
| `LoadQueries` | `semesterItems`, `groupItems`, `item` (`LoadItemView`), `weeklyUnits(semesterId)` (units for generation), `pattern(hours)`, `teachesInSemester(teacher, discipline, semester)` (candidate ranking) |
| `LoadPattern` | DOMAIN §4: `p = hours / hoursPerPair`, whole = ⌊p⌋, remainder ≥ 0.25 → one HALF, ≥ 0.75 → one more whole (3 h → 1+½, 4 h → 2, 1 h → ½) |
| `WeeklyUnit` | `unitId = <loadItemId>:<subgroup>:<index>`, WHOLE/HALF, teacher of that subgroup, room requirements |

## Endpoints

- `/api/admin/load?semesterId=&groupId=` (semesterId required) → `[LoadItemDto]` sorted by group and
  discipline; `GET/POST/PUT/DELETE /{id}`. `LoadItemDto` = CONTRACTS fields + `planSource` (AUTO | FIXED), `streamId`,
  `note`, `version`.
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
- Hours per week 0.1..40 with one decimal.
- **Planned hours** (owner's rule 15.11.2026, DOMAIN §9): weeks the group is off theory (DAY_OFF and SUSPEND periods: НС,
  practice, session, training camp, holidays) are not planned and not caught up. `plan_source` (`V1_302`):
  - AUTO — counted by `LoadPlan` from the group's study days (`StudyCalendar.theory`, per weekday and parity) and the
    item's lessons in the PUBLISHED version: `2 × hoursPerWeek × T / C` (T = the lessons' study days, C = the template's
    pairs per two weeks; a split item takes its busiest subgroup), without lessons `hoursPerWeek × studyDays / 5`;
  - FIXED — a number set in the form or by an import row (a program's 40 h); never recounted. A CURRICULUM row's total
    does not fix the plan (it assumes the curriculum's weeks). An import row without a number keeps a FIXED plan.
  - The form: an empty field is AUTO; a number is FIXED; the number of an AUTO plan sent back unchanged with the same
    hours per week stays AUTO (older forms, the undo journal). Derived items are always AUTO. `new LoadItem(…, hours)`
    in Java is FIXED; the services hand the plan to the system with `autoPlan`.
  - Recount (`LoadPlanner.recount`, `LoadPlanEvents`): on `CalendarEdited` (the groups it names, in the semesters its
    dates fall in, one calendar read whatever the dates), on `TimetableVersionPublished` (the whole semester with that
    version's lessons) and once at start-up for every semester with load (plans stored by the old rule; a semester that
    fails is logged and left for its next edit or publication, core still starts). Both run in the transaction of the
    change and take the semester's load lock first, so a dry run or a refused edit rolls them back and a concurrent load
    write waits for the new calendar. Lock order is the publication's: the timetable's `CalendarEdited` listener runs
    first (`@Order`: it may refuse the edit and takes the publication locks), the recount last, so an edit and a
    publication of one semester never wait for each other's lock in opposite order. A context reads a constant number of queries: the
    study calendar, the groups' courses and one slot query (`TemplateLessonReader.publishedSlots`/`versionSlots`).
  - `V1_302` marks existing plans that differ from the old default `round(hoursPerWeek × teachingWeeks)` FIXED, the
    rest AUTO. CURRICULUM items stay AUTO whatever their number: the import stored the curriculum's own total (22 weeks
    × hours for 4КІ), which by the owner's rule does not fix the plan (`LoadPlanSourceMigrationIT`).
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
  Derivation keeps one teacher and no room type per item. Generation does not lose what it drops (genfix,
  timetable.md «Generation»): a unit matched to a base lesson keeps that lesson's teacher (B3); the base rooms
  go to the scheduler in `initial`, which keeps PE in the hall with the soft rule `specialRooms` (no hard room
  type is derived); the stored item is not changed.
- **Template lessons are read with SQL** (`TemplateLessonReader`), not through the timetable module: the
  timetable module depends on load (generation), so a Java dependency back would be a module cycle.
- `LoadSubgroupUsage` implements the catalog SPI: a subgroup with a load teacher cannot be deleted.
- **One item per natural key**: semester × group × discipline × lesson kind × split is a unique index
  (`uq_load_item_natural_key`, V1_301, which first merged existing copies into the oldest item). Create, update,
  import and derivation take a per-semester advisory lock (`LoadItemRepository.lockSemesterLoad`) before they
  read, so a double-clicked derive or an importer retry racing another writer waits and then updates the
  stored item instead of adding a copy that would double the planned hours; a writer that skips the check
  gets the generic 409 «Конфлікт даних».

## How to test

`LoadPatternTest`, `LoadDerivationTest`, `LoadPlanTest` (НС block, practice block, numerator-only discipline, holiday
for all, no lessons, scaled hours, split cohort), `LoadApiIT` (CRUD and pattern, split validation, weekly units,
subgroup guard, derivation incl. skip, idempotency and two derivations at once, the unique key, import; AUTO plan
follows a period both ways, a FIXED plan stays, the echo rule, import keeps a FIXED plan, the published timetable makes
the plan exact, a parity override and new semester dates recount it), `LoadPlanEventsTest` and `LoadPlanEventsIT`
(start-up recount of stored AUTO plans, a failing semester does not stop start-up, the timetable's calendar listener
runs before the recount), `LoadPlanSourceMigrationIT` (`V1_302`: a set plan FIXED, a curriculum total AUTO).
