# Module `calendar` (core)

Academic years, semesters (one active), week numbering and parity with overrides, bell profiles,
period kinds («Види періодів») and calendar periods (holidays, practice, НС, study modes, exam sessions), the
import of the college's academic calendar and theory weeks per group, shift windows; the public meta and bells;
the internal catalog snapshot for the importer.

## API for other modules (base package `ua.vtc.rozklad.core.calendar`)

`CalendarQueries`:

| Method | Rule (DOMAIN §2) |
|---|---|
| `activeSemester()`, `semester(id)`, `semesterOn(date)` | `SemesterInfo` incl. `teachingWeeks` = `weeksCount` or the Monday-based weeks the dates span |
| `week(date)` | `WeekInfo {semesterId, weekNumber, weekStart, parity, overridden}`; empty outside semesters |
| `parity(date)`, `weekNumber(date)` | 422 `DATE_OUTSIDE_SEMESTER` for a date no semester contains |
| `isStudyDay(date)` | Mon–Fri, inside a semester, no period of a DAY_OFF kind with scope ALL |
| `isGroupStudyDay(date, groupId)` | additionally no DAY_OFF or SUSPEND period whose scope includes the group (ALL, its course, the group) |
| `studyModeFor(date, groupId)` | most specific ONLINE period (groups > courses > all, then later start); OFFLINE by default |
| `periodsOn(date, groupId)`, `orderNotes(date)` | periods for day notes (`PeriodInfo` with `periodKind` and `roomId`); order notes of all periods active on the date (DOMAIN §8) |
| `bellProfileOn(date)`, `bellSlots(date)`, `pairTime(date, pair)` | profile = the semester's, else `settings.activeBellProfileId`; weekends have no slots |
| `currentPair(instant)`, `nextPair(instant)` | Kyiv time; only on study days; `minutesLeft` rounded up |
| `shiftWindow(shift, weekday)`, `shiftWindows()` | shift 1 → pairs 1–4; shift 2 → 5–8 Mon–Thu, 4–6 Fri |
| `shiftWindowTable()` | the whole table as `ShiftWindows` for rule engines; the DOMAIN §2 windows when the table is empty (logged once) |
| `studyCalendar(from, to)`, `studyCalendar(semesterId)` | batch read model `StudyCalendar` (semesters, parity with overrides, periods) built with a constant number of queries — use it over date ranges instead of per-date calls |
| `theoryWeeksFromGraph(semesterId)` | group → theory weeks of the academic calendar (`group_semester`), one query; a cross-check only |
| `bellTimes(StudyCalendar)` | semester id → `BellTimes` with the same profile rule as `bellProfileOn` |

Value types for bulk rules (moved here from the timetable module at integration): `StudyCalendar`
(`day(date, GroupScope)` → `DayInfo {parity, weekNumber, studyDay, online, note, semesterId}`,
`studyDates`, `theory(semesterId, GroupScope)` → `GroupTheory` (the group's study days per weekday and parity, its
theory weeks — weeks with any study day — by parity, and the DAY_OFF/SUSPEND periods that take it off its pairs; the
plan of the load module counts these days, owner's rule 15.11.2026), `practices(date, GroupScope)` and
`supervisedPractices(date, teacherId)`: the blocks of the day —
SUSPEND periods of a group / of their supervisor on Mon–Fri dates no applicable DAY_OFF closes), `StudySemester`,
`StudyPeriod` (`kind: PeriodKindInfo {id, code, name, color, effect}`, `roomId`, `effect()`, `closesLessons`, `isDayOff`,
`suspends`, `marksOnline`, `appliesTo`, `decidingStudyMode`; the legacy-kind constructors build the builtin kind for tests),
`GroupScope`, `BellTimes`, `ShiftWindows`. Rules ask the effect, never the code; `PeriodRules` (entity-based, per date)
and `StudyCalendar` (batch) share the table of DOMAIN §2. ONLINE periods do not add up: `DayInfo.online`
comes from the one deciding period (groups > courses > all, then the later start, then the id), the same
rule as `studyModeFor`. So a group OFFLINE period inside a course ONLINE period brings that group back to its
rooms (its dated lessons are offline and room-exclusive), and the note shows only the deciding online period. Every module (timetable, substitution, order, accounting) reads the
calendar only through `CalendarQueries` and these types; their former private parity calculators and
bell/period readers were removed.

`SemesterCalendar` is the pure rule (week 1 = the Monday-based week containing `startsOn`, odd weeks
= `firstWeekParity`, an override wins for its week only) for bulk use, e.g. materialization:
`SemesterCalendar.of(semester, overrides)`. Verified: start 01.09.2026 ⇒ 19.10.2026 is week 8,
ЗНАМЕННИК (order №35).

## Endpoints

- `/api/admin/semesters` CRUD (newest first), `POST /{id}/activate`, `GET /{id}/weeks`
  (`{weekStart, weekEnd, weekNumber, parity, computedParity, overridden}`), `PUT /{id}/weeks/{weekStart}`
  `{parity}` (`null` removes) and `DELETE /{id}/weeks/{weekStart}`.
- `/api/admin/bell-profiles` CRUD; `PUT` replaces all slots; `defaultProfile` marks the profile of settings.
- `/api/admin/calendar-periods?semesterId=` CRUD (`kindId`/`roomId`; legacy `kind` accepted one release).
- `/api/admin/period-kinds` CRUD, `PUT …?dryRun=true` → `PeriodKindImpactDto`.
- `GET /api/admin/group-semesters?semesterId=`.
- `POST /api/admin/import/calendar-periods[?dryRun=true]` (ADMIN, SERVICE_IMPORTER).
- `/api/admin/shift-windows` `GET`/`PUT` with the whole table (10 rows).
- `GET /api/public/meta`, `GET /api/public/bells?date=`.
- `GET /api/internal/catalog/snapshot` (SERVICE).

## Decisions

- **Semesters never overlap** and `(academic year, number)` is unique; the academic year is found or
  created from `academicYear` («2026/2027») or derived from `startsOn` (July–December → Y/Y+1).
  Name defaults to «I семестр 2026/2027». Activation clears the old flag and flushes first (the
  «one active» unique index is not deferrable). The active semester cannot be deleted.
- Changing semester dates keeps overrides inside the new range only and is refused while periods
  would stick out.
- **Periods** lie inside their semester; a kind with effect ONLINE needs `studyMode`, other kinds must not have one;
  supervisors and a room only for SUSPEND; scope ALL has no courses/groups, COURSES needs courses, GROUPS needs existing
  groups. The kind is resolved from `kindId`, else from the legacy `kind` (its builtin), else 400; an inactive kind cannot
  be chosen anew (an existing period keeps it). A PUT on an imported row makes it MANUAL.
- **Period kinds** (`V1_203`): 15 builtin rows with fixed ids (`CalendarPeriodKind.builtinKindId()`), codes immutable,
  switched off instead of deleted; a code or alias may not repeat another kind's code or alias (case ignored, checked in
  `PeriodKindService`). The kind is read in the same query as its periods (`join fetch p.kind` in `StudyCalendarLoader` and
  `CalendarPeriodRepository`, `@EntityGraph` on the derived queries), so no SQL budget grew. The legacy column
  `calendar_period.kind` is written from the kind (`CalendarPeriodKind.of`: К/БЗ/НП/ФН/І keep theirs, other kinds by
  effect) and resynced when a kind's effect changes; nothing reads it, and no check is keyed on it any more (`V1_204`
  dropped `ck_calendar_period_study_mode_required`, which broke every new ФН period once ФН left ONLINE).
- **Effect change** of a kind with periods: blocked (409 plain) to/from ONLINE and to a non-SUSPEND effect while a period
  has a room or supervisors; otherwise one `CalendarEdited` + `schedule.changed` over the periods' dates and groups (lessons
  from today on, live-change clashes → 409 `conflicts[]`). The dry run runs the same path with
  `TransactionAspectSupport…setRollbackOnly()`, catching the listener's `ConflictException` to report it;
  `CalendarEdited.outcome()` carries the lesson counts back from the timetable listener (calendar cannot depend on timetable);
  the caller passes its own `Outcome` to `CalendarChangePublisher.periodsChanged`, so a dry run that catches the 409 still
  reports the counts.
  A new name or colour publishes only `schedule.changed` (`CalendarChangePublisher.textsChanged`).
- **Academic calendar import** (`CalendarPeriodImportService`): all references are resolved first (one 400 for the whole
  request; kinds once, groups once through `CatalogQueries.allGroups` and canonical codes, rooms once per name); an
  advisory lock per academic year serializes concurrent imports; items are cut to the year's semesters
  (`key` or `key|S<n>`); plan (CREATE/UPDATE/UNCHANGED/SKIP_MANUAL/DELETE) before any write; `before` read before the
  writes; one `CalendarEdited` for every written row (old and new state); `group_semester` upserted by JDBC
  (`GroupSemesterService`, MANUAL rows untouched). A file with nothing new writes and publishes nothing. `manualOverlaps`
  lists manual rows of the same effect sharing a group and dates with an item, except those in `manual`: hand-made rows
  and edited imported rows whose key the file no longer produces (otherwise a changed run would hide the stale row).
- **`schedule.changed` (CALENDAR_CHANGED)** is published (outbox) for a parity override (the week's
  Mon–Fri, all groups), for every period create/update/delete (old and new dates, the groups
  in scope) and for a semester whose dates or first-week parity change (every weekday of the old and
  new range, all groups). «All groups» includes inactive ones (`CatalogQueries.allGroups`): a publication
  materializes their lessons too, so a holiday or a new semester end must reach them. Timetable
  materialization listens to the same in-process event.
  Bell profile edits change only times and publish nothing.
- **Bells**: the default bell profile in settings must exist and be active (`SettingsConstraint`
  implemented here); it cannot be deleted or deactivated. A profile used by a semester → 409 on delete.
  Slots of a weekday must be unique per pair and not overlap in pair order.
- **Public meta `nextStudyDay`** (added 08.11.2026 for the bot): the first date after today that is a
  study day for the whole college (`StudyCalendar.day(date, null)`: Mon–Fri, inside a semester, no
  HOLIDAY/NO_CLASSES with scope ALL), with its week number and parity (overrides applied); one batch
  calendar read over 120 days; `null` when there is none.
- **Public meta**: semester of today, otherwise the active one; `weekNumber`/`parity` are `null` when
  today is outside it (no invented parity between semesters). `bells` returns 404 when neither the
  semester nor settings name a profile; `profiles` only without `date`; `weekday` is ISO 1..7.
- **Snapshot lives here**: it combines catalog data with semesters; calendar already depends on
  catalog, the reverse would be a module cycle.
- **Reference data** (migration `V1_201`): «2 зміни, 60 хв» (active, default), «2 зміни, 50 хв» and
  «1 зміна, 80 хв» (inactive alternatives from DOMAIN §2), shift windows. Inserted only when missing.
- **Bell times bind directly as `LocalTime`** (`@JdbcType(LocalTimeJdbcType.class)` on `BellSlot`).
  With `hibernate.jdbc.time_zone: UTC` the default binding goes through `java.sql.Time` and shifts the
  wall-clock time by the JVM offset on 1970-01-01 (Europe/Kyiv: 08:30 read as 11:30). The test JVMs run
  in UTC, so `CalendarTimeZoneIT` switches the default zone to Europe/Kyiv. Dates (`LocalDate`) are not
  affected (checked by the same test). Other modules' `LocalTime` columns have the same risk; a global
  `hibernate.type.java_time_use_direct_jdbc: true` would fix all of them at once.

## Demo seed (profile `demo`)

`db/demo/V9_001__demo_calendar_and_structure.sql` (added to Flyway by `shared.persistence.DemoSeedConfig`):
academic year 2026/2027, semesters I (01.09–30.12.2026, NUMERATOR, active) and II (19.01–30.06.2027),
3rd course online 01.09–31.10.2026 with its order note. `DemoGroupPeriods` adds the group periods of
order №35 (practice 2КІ3 19.10–30.10.2026, 4КІ online 01.09–31.10.2026) at startup and after every
catalog import, once the groups exist. Autumn holidays are not assumed (DOMAIN §12). A database
migrated with the profile must keep it (the V9 rows are in Flyway history).

## How to test

`SemesterCalendarTest` (week 8 / ЗНАМЕННИК, overrides), `GroupTheoryTest` (НС block, holidays for all and a course
block, online kept, partial block, parity override), `PeriodRulesTest` and `StudyCalendarTest` (the old kinds as
before, custom kinds by effect, priority, exam session suspends), `PairClockTest`,
`CalendarApiIT` (reference data, weeks, overrides, activation, periods, meta/bells, profile guards,
snapshot security), `PeriodKindApiIT` (builtin seed, kind CRUD and validation, periods with `kindId`/`roomId`/legacy
`kind`, inactive kinds, blocked effect changes, group semesters, snapshot `periodKinds`), `CalendarPeriodImportIT`
(dry run writes nothing, idempotent re-run, semester split, out of semester, manual rows, prune, validation, roles),
`PeriodKindLessonsIT` (timetable package: a new SUSPEND kind removes pairs, shows its block and note; effect change dry run
and apply; 409 for live changes in the kind and import dry runs), `CalendarTimeZoneIT` (times and dates under
Europe/Kyiv), `DemoSeedIT` (demo SQL against the real schema incl. the legacy-kind trigger, rolled back).
