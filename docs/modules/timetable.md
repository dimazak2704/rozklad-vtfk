# core · timetable — versions, conflicts, publication, schedules, generation

Package `ua.vtc.rozklad.core.timetable` (Spring Modulith module `timetable`). Specs: DOMAIN §2, §4–§6,
CONTRACTS §1, §2 (timetable, generation, import apply), §7 (events). Deviations and clarifications are in
`docs/CHANGELOG-CONTRACTS.md` («Timetable»).

## 1. What the module does

- **Weekly template** (`TimetableVersion` + `TemplateLesson`): versions DRAFT → PUBLISHED → ARCHIVED, editor
  CRUD on drafts, drag & drop move with `?dryRun=true`, copy, delete drafts, diff of two versions.
- **One conflict engine** (`internal.conflict.ConflictEngine`) for the template (weekday × pair × parity) and for
  dated lessons (date × pair). Hard: teacher, cohort (whole group vs subgroup), room (`maxParallel`, streams
  count once), capacity, room type, shift window (weekly template only — dated lessons of changes and orders may be
  on any pair 1–8, DOMAIN §2), teacher UNAVAILABLE. Soft: windows of groups (per subgroup) and
  teachers, «рваний день», more than 4 pairs a day, a discipline 3+ times a day, UNWANTED slots. Every conflict is a
  `ConflictDto` with a Ukrainian message; template conflicts carry `weekday`, dated ones `date`.
- **Metrics** of a template (`MetricsDto`) and **diff** by (group, subgroup, weekday, pair, parity, discipline,
  teacher, room).
- **Publication** (DOMAIN §6): hard conflicts → 409; previous published version → ARCHIVED; dated lessons from
  `effectiveFrom` to the end of the semester are replaced in bulk (JDBC batches); lessons of applied changes are
  kept and their conflicts with the new version reported; events `timetable.version.published` and
  `schedule.changed` (outbox) and SSE `schedule.changed`.
- **Public schedules** for a group, teacher or room (one SQL query per range, ETag), `LessonView` kinds with the
  original taken from the change row, and **ICS** subscriptions for groups and teachers.
- **Import apply** of a recognized timetable → DRAFT (source IMPORT), names resolved through catalog names and
  aliases with the normalization of DOMAIN §10.
- **Generation orchestration**: builds the `ProblemSnapshot`, sends `generation.requested` (outbox), consumes
  `generation.progress` / `generation.completed` from `core.q.scheduler-results` idempotently, stores progress points,
  turns a SOLVED result into a DRAFT (source GENERATED) with metrics «було / стало», pushes SSE; cancel and a watchdog
  for silent schedulers.
- **Usual rooms of teachers** (round 7, DOMAIN §5): public `TeacherRoomQueries.published(semesterId, teacherIds)` →
  `TeacherRooms` (`forTeacher`, `forDiscipline`, `forGroup`), implemented by `internal.support.TeacherRoomReader`
  (one aggregate SQL over `template_lesson` per call; `ofVersion(versionId)` for the generator). Used by the
  snapshot builder (`units[].teacherRoomId`) and by substitution (`SubstituteRooms`).

## 2. Endpoints

| Method & path | Answer |
|---|---|
| `GET /api/public/schedule/{group\|teacher\|room}/{id}?from&to` | `ScheduleDto`; default range = current week; ≤ 31 days else 400; `Cache-Control: no-cache, public` + ETag (304) |
| `GET /api/public/ics/{group\|teacher}/{id}.ics` | `text/calendar` of the current semester (scheduled lessons, UTC times) |
| `GET /api/admin/timetable/versions?semesterId` | versions with `lessonCount`, `hardConflicts`, `softWarnings`, `metrics` (active semester by default) |
| `POST /api/admin/timetable/versions` | 201 draft (`copyFromVersionId` copies lessons) |
| `GET` / `DELETE /api/admin/timetable/versions/{id}` | one version / delete a draft (204, 409 otherwise) |
| `GET /api/admin/timetable/versions/{id}/lessons?groupId&teacherId&roomId` | `[TemplateLessonDto]` |
| `POST/PUT/DELETE /api/admin/timetable/versions/{id}/lessons[/{lessonId}]` | `{lesson, conflicts}` of the touched slots |
| `POST …/lessons/{lessonId}/move[?dryRun=true]` | 200 `{lesson, conflicts}` / 409 `conflicts[]` |
| `GET …/versions/{id}/conflicts` · `/metrics` · `GET …/versions/{a}/diff/{b}` | `{hard, soft}` · `MetricsDto` · `{added, removed, moved}` |
| `POST …/versions/{id}/publish` `{effectiveFrom}` | 200 `{materializedLessons, keptLessons, coveredByChanges, conflicts}` / 409 / 422 |
| `POST /api/admin/import/timetable` (ADMIN, SERVICE) | 201 `{versionId, name, lessonCount, hardConflicts, softWarnings}` / 422 `errors[]` |
| `POST /api/admin/generation/jobs` · `GET …/jobs?semesterId` · `GET …/jobs/{id}` · `POST …/jobs/{id}/cancel` | 202 `{jobId}` · jobs · job · 202 job |

Public API for other modules (base package): `ScheduleQueries` (schedules and `LessonView`s — e.g. for
`GET /api/me/schedule` and substitution screens), `LessonConflictChecker` (dated conflicts and previews for
substitutions, swaps, extra pairs), the view records (`ScheduleView`, `LessonView`, refs) and the entities/repositories.

## 3. Decisions

- **No Java dependency on `load` and `substitution`.** Both depend on timetable (derive load from a version; lessons
  of changes), so a dependency back would be a module cycle that `ApplicationModules.verify()` rejects. Timetable reads
  `load_item(_teacher)`, `teacher_preference` and `change` with SQL read models (`LoadReadRepository`,
  `PreferenceReadRepository`, `ScheduleReadRepository`, `DatedLessonReadRepository`). Catalog, calendar and settings
  are read through their repositories (they are upstream and must not depend on timetable).
- **Calendar through `CalendarQueries`** (integration): week parity (DOMAIN §2, overrides win), study days, periods
  (HOLIDAY/NO_CLASSES/PRACTICE close the day for their scope, STUDY_MODE ONLINE/MIXED marks lessons online,
  EXAM_SESSION only adds a note), bell times and shift windows come from the calendar module's batch API
  (`studyCalendar`, `bellTimes`, `shiftWindowTable`). The former private `internal.calendar` package was moved
  there. The bell profile rule is the calendar's one: the semester's profile, else `calendar.activeBellProfileId`.
- **Reason text** of a `LessonView` is `shared.domain.ReasonCode.printed(...)`, the same rule as the order column
  (ADDITIONAL, BLOCK and room-only changes print none). Import name matching uses `catalog.CatalogText.key` and
  `GroupCodes.canonical`; unit expansion uses `load.LoadPattern`; `accounting.hoursPerPair` comes from `SettingsService`.
- **Shift windows**: table `shift_window`; when it is empty the DOMAIN §2 windows are used (logged once). A weekday
  without a row is not restricted. `ShiftWindowRule` skips dated lessons (round 7): a republication no longer
  reports a SHIFT conflict for a lesson a published order put on a 5th pair of a 1st-shift group.
- **Bell times**: the semester's profile, otherwise the first active profile by name; without any profile lesson
  times are `null` (logged).
- **Cohorts**: a split group is evaluated per subgroup (whole-group lessons + its own); a window every cohort has is
  reported once for the group. Capacity uses the subgroup size when it is known; unknown sizes/capacities skip the check.
- **Streams**: lessons with the same `streamId` share teacher and room (one occupancy, sizes summed for capacity).
- **Move** refuses only hard conflicts of the moved lesson itself (pre-existing conflicts elsewhere do not block);
  create/update/delete allow conflicts in a draft and report those of the touched slots.
- **Publication**:
  - serialized per semester (advisory lock) and per version (row lock; edits take the same lock);
  - `effectiveFrom` must be inside the semester; when a version is already published it must not be in the past
    (past lessons happened and count in accounting); the first publication may start in the past (seed);
  - deleted: TEMPLATE lessons from `effectiveFrom` without `change_id` and not referenced by `change.original_lesson_id`;
    everything else is kept;
  - a template occurrence whose cohort slot holds a kept lesson (scheduled or cancelled) is left to the change
    (`coveredByChanges`); one whose teacher or exclusive room is taken by a kept scheduled lesson is not created and
    returned as a HARD conflict, so the dispatcher can fix that date with a change; hard conflicts of kept lessons with
    the new lessons are reported too;
  - metrics of the published template are stored in `timetable_version.metrics`.
- **LessonView**: kind from origin and change kind (REPLACE_* / DISCIPLINE_FINISHED → SUBSTITUTED, SWAP → MOVED,
  ROOM_CHANGE → ROOM_CHANGED, ADDITIONAL, BLOCK; cancelled → CANCELLED). `original` = discipline/teacher/room of
  the change row plus the original pair when moved. A cancelled original is hidden when its replacement (same change)
  is in the same view (group view shows the substitution once; the absent teacher still sees the cancelled pair).
  `reasonText` is the public order text («Відп.», «Зв.зам.», «Вичит.», «Відр.», or the free text of OTHER).
- **Metrics**: per group, weekday and kind of week: windows of the worst cohort, late pair = the last pair of the
  group's shift window (1st shift 4th, 2nd shift 8th / Friday 6th), max pairs of a cohort per day, broken days per
  group-day; teacher windows per weekday and kind of week.
- **Import**: exact name first, then a normalized key (NFC, lower case, apostrophes, Latin look-alikes → Cyrillic in
  Cyrillic names incl. short initials, dots/spaces removed; group codes canonical «ІКІ1» → «1КІ1», «4КИ1» → «4КІ1»)
  over names and aliases (also the stored `normalized`). A key shared by two entities is ambiguous and never guessed.
  Any unresolved name → 422 with one entry per field and spelling; nothing is saved.
- **Generation**:
  - units per DOMAIN §4 with `accounting.hoursPerPair` (default 2); split items give units per subgroup (subgroup
    teachers, otherwise the catalog subgroups with the whole-group teacher; neither → 422);
  - scope = listed groups ∪ active groups of listed courses (all active groups when empty); base version = given one
    (same semester) or the published one; base lessons of groups outside the scope are pinned units, pinned lessons
    of in-scope groups pin the matching unit (by load item, else by discipline and teacher) or stay as their own unit;
  - unknown group size → 0, unknown room capacity → `Integer.MAX_VALUE` in the snapshot (no invented violations);
    timeslots = union of the shift windows;
  - every free unit carries `teacherRoomId` = `TeacherRooms.forDiscipline(teacher, discipline)` over the published
    version of the semester (the base version when nothing is published; null for a teacher without lessons and for
    pinned units); `homeRoomId` is sent as null since round 7 (rooms belong to teachers, DOMAIN §5);
  - one active job per semester (check + partial unique index); results are idempotent (`processed_event`), events
    of finished/unknown jobs are ignored; the payload type is chosen by routing key; malformed messages go to the DLQ;
  - SOLVED → DRAFT «Згенеровано dd.MM.yyyy HH:mm» (WHOLE → BOTH, HALF → returned parity; a pinned unit left out stays
    at its pin; unplaced units are noted in the version notes); an invalid answer (unknown/duplicate unit, off-grid
    slot, HALF without week) fails the job instead of being repaired;
  - cancel marks the job CANCELLED at once and sends `generation.cancel`; the watchdog fails QUEUED jobs after
    time limit + grace and RUNNING jobs after grace without progress (`app.timetable.generation.timeout-grace`,
    default 10 min) and tells the scheduler to stop.
- **SSE** is pushed after commit (`shared.sse.SseAfterCommit`), not through the outbox: `generation.progress`
  `{jobId, elapsedMs, hardScore, softScore}` and `generation.completed` (job DTO) to ADMIN, `schedule.changed`
  `{dates, groupIds, teacherIds, reason}` to everybody.

## 4. Configuration

`application.yml` → `app.timetable.generation.timeout-grace` (`GENERATION_TIMEOUT_GRACE`, default `10m`),
`watchdog-interval` (`GENERATION_WATCHDOG_INTERVAL`, default `PT1M`). Migration `V1_401__generation_results.sql`
(job plan, analysis, metrics before/after, base version, last progress; one active job per semester).

## 5. How to test

```bash
source scripts/env.sh
mvn -q -pl services/core -am test -Dtest='ua.vtc.rozklad.core.timetable.**.*Test' -Dsurefire.failIfNoSpecifiedTests=false
mvn -q -pl services/core -am verify -Dit.test='TimetablePublicationIT,GenerationFlowIT' -Dtest='None*' \
    -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false
```

- Unit: `ConflictEngineTest` (every rule, template and dated), `calendar.StudyCalendarTest`,
  `MaterializationPlannerTest` (parity, overrides, holidays, practice, online, kept changes), `TimetableMetricsCalculatorTest`
  (4КІ1-like week: two halves in one slot remove both broken days), `VersionDiffCalculatorTest`, `LoadUnitExpanderTest`,
  `GeneratedLessonsBuilderTest`, `GenerationJobMapperTest`, `IcsCalendarWriterTest`, `LessonViewAssemblerTest`,
  `NameKeysTest`, `CatalogNameIndexTest`.
- Web slices: version, lesson (409 payloads, dryRun), public schedule (ETag/304, ranges, ICS), generation, import (SERVICE, 422).
- Integration (Testcontainers): `TimetablePublicationIT` (import → publish → materialization, REST schedule and ICS,
  applied substitution kept on republication with a reported conflict, events on RabbitMQ, 409/422 rules),
  `GenerationFlowIT` (snapshot on RabbitMQ, teacher rooms in the units, progress/completed consumption, generated
  draft, redelivery ignored, cancel, invalid and failed results). `TeacherRoomsTest` (weights, ties by room number,
  fallbacks).

## 6. Known gaps

- Calendar edits (new holiday, practice) do not re-materialize published lessons yet: the calendar module should
  publish an application event that timetable listens to (no Java dependency from calendar to timetable).
- Streams are not in the `ProblemSnapshot` contract (no field), so the solver plans stream units separately.
- `GET /api/me/schedule` is mapped by `MyScheduleController` (integration): the teacher view of `ScheduleQueries`
  for the signed-in teacher (`CurrentUserService.requireTeacherId()`, 403 without a linked teacher), same
  `from`/`to` defaults and 31-day limit as the public schedule.
- Version list analyzes every version of the semester on each call (fine for tens of versions; cache if it grows).
