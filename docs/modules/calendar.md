# Module `calendar` (core)

Academic years, semesters (one active), week numbering and parity with overrides, bell profiles,
calendar periods (holidays, practice, study modes, exam sessions), shift windows; the public meta
and bells; the internal catalog snapshot for the importer.

## API for other modules (base package `ua.vtc.rozklad.core.calendar`)

`CalendarQueries`:

| Method | Rule (DOMAIN §2) |
|---|---|
| `activeSemester()`, `semester(id)`, `semesterOn(date)` | `SemesterInfo` incl. `teachingWeeks` = `weeksCount` or the Monday-based weeks the dates span |
| `week(date)` | `WeekInfo {semesterId, weekNumber, weekStart, parity, overridden}`; empty outside semesters |
| `parity(date)`, `weekNumber(date)` | 422 `DATE_OUTSIDE_SEMESTER` for a date no semester contains |
| `isStudyDay(date)` | Mon–Fri, inside a semester, no HOLIDAY/NO_CLASSES period with scope ALL |
| `isGroupStudyDay(date, groupId)` | additionally no HOLIDAY/NO_CLASSES/PRACTICE whose scope includes the group (ALL, its course, the group) |
| `studyModeFor(date, groupId)` | most specific STUDY_MODE period (groups > courses > all, then later start); OFFLINE by default |
| `periodsOn(date, groupId)`, `orderNotes(date)` | periods for day notes; order notes of all periods active on the date (DOMAIN §8) |
| `bellProfileOn(date)`, `bellSlots(date)`, `pairTime(date, pair)` | profile = the semester's, else `settings.activeBellProfileId`; weekends have no slots |
| `currentPair(instant)`, `nextPair(instant)` | Kyiv time; only on study days; `minutesLeft` rounded up |
| `shiftWindow(shift, weekday)`, `shiftWindows()` | shift 1 → pairs 1–4; shift 2 → 5–8 Mon–Thu, 4–6 Fri |
| `shiftWindowTable()` | the whole table as `ShiftWindows` for rule engines; the DOMAIN §2 windows when the table is empty (logged once) |
| `studyCalendar(from, to)`, `studyCalendar(semesterId)` | batch read model `StudyCalendar` (semesters, parity with overrides, periods) built with a constant number of queries — use it over date ranges instead of per-date calls |
| `bellTimes(StudyCalendar)` | semester id → `BellTimes` with the same profile rule as `bellProfileOn` |

Value types for bulk rules (moved here from the timetable module at integration): `StudyCalendar`
(`day(date, GroupScope)` → `DayInfo {parity, weekNumber, studyDay, online, note, semesterId}`,
`studyDates`), `StudySemester`, `StudyPeriod` (`closesLessons`, `marksOnline`, `appliesTo`), `GroupScope`,
`BellTimes`, `ShiftWindows`. Every module (timetable, substitution, order, accounting) reads the
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
- `/api/admin/calendar-periods?semesterId=` CRUD.
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
- **Periods** lie inside their semester; STUDY_MODE needs `studyMode`, other kinds must not have one;
  scope ALL has no courses/groups, COURSES needs courses, GROUPS needs existing groups.
- **`schedule.changed` (CALENDAR_CHANGED)** is published (outbox) for a parity override (the week's
  Mon–Fri, all active groups), for every period create/update/delete (old and new dates, the groups
  in scope) and for a semester whose dates or first-week parity change (every weekday of the old and
  new range, all active groups). Timetable materialization can listen to the same in-process event.
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

`SemesterCalendarTest` (week 8 / ЗНАМЕННИК, overrides), `PeriodRulesTest`, `PairClockTest`,
`CalendarApiIT` (reference data, weeks, overrides, activation, periods, meta/bells, profile guards,
snapshot security), `CalendarTimeZoneIT` (times and dates under Europe/Kyiv), `DemoSeedIT` (demo SQL
against the real schema, rolled back).
