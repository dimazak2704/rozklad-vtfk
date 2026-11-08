# Integration of the parallel modules (08.11.2026)

What the integration step merged, how overlapping work was reconciled, and what is still open
between the web client and the backend. Module details live in the other files of this folder.

## 1. What was merged (cherry-picked in this order, linear history)

| Area | Commits |
|---|---|
| core: catalog, settings, calendar, load, identity | catalog and settings modules, shared web helpers · calendar, load and identity modules with reference data · docs |
| core: timetable | conflict engine, calendar rules and metrics · versions, publication, schedules and import · generation orchestration |
| core: substitution, order, accounting | absences, changes, ranked options and accounting · orders, publication, public changes and dashboard · docs |
| scheduler | timetable model, constraints and score analysis · generation jobs over RabbitMQ · docs |
| importer | parsers · jobs, catalog matching, AI extraction, apply and parse-absence |
| notification | Telegram bot, delivery queue, events and order documents · tests · docs |
| web | public site and teacher cabinet · dispatcher pages · timetable editor and generator · catalog, calendar, load, accounting and import pages |

The integration ran on branch `integration` in its own worktree (main was being edited by the design
pass at the same time) and was rebased onto main after `web: second/third design pass` landed; main was
then fast-forwarded. Conflicts were only in append-only files (`application.yml` blocks of identity, timetable and
substitution; sections of `docs/CHANGELOG-CONTRACTS.md`); both sides were kept. Commit trailers of the
cherry-picked commits were removed as the integration task asked.

## 2. One public API per concern (core)

| Concern | The API | Removed duplicates |
|---|---|---|
| Calendar rules: semester of a date, parity, study days, periods, bells, shift windows | `calendar.CalendarQueries` + value types `StudyCalendar`, `StudySemester`, `StudyPeriod`, `DayInfo`, `GroupScope`, `BellTimes`, `ShiftWindows` (moved from `timetable.internal.calendar`) | `timetable.internal.calendar.*` (`WeekParityCalculator`, private `SemesterCalendar`, loaders), `shared.time.StudyWeeks`, repository reads in `SubstitutionCalendar`, `OrderCalendar`, `AccountingCalendar` (now thin adapters that only add the modules' 422 messages) |
| Bell profile in effect | the semester's profile, else setting `calendar.activeBellProfileId` (reference profile «2 зміни, 60 хв») | timetable's «first active profile by name», substitution/accounting's «the only active profile» |
| Settings | `settings.SettingsService` | `SubstitutionSettings`, `OrderSettings`, `AccountingSettings`, `timetable…HoursPerPair` (the confirmation timeout default is now the settings default, 120 min, not 60) |
| CONTRACTS refs | `catalog.GroupRef/TeacherRef/DisciplineRef/RoomRef` (+ `CatalogQueries.*Refs` batch maps) | `shared.ref.*`, `timetable.*Ref` |
| Dated lesson views | `timetable.ScheduleQueries.lessonViews` | substitution's own `LessonView` record and mapping |
| Printed reason («Відп.», «Зв.зам.» …) | `shared.domain.ReasonCode.printed(kind, code, text)` (moved from `substitution`, timetable must not depend on substitution) | `timetable…ReasonTexts` |
| Ukrainian wording | `shared.text.UkrainianText` | `timetable…support.UkrainianText` (schedule labels keep the Ukrainian apostrophe «ПʼЯТНИЦЯ», orders the ASCII one of the college documents) |
| Name normalization (DOMAIN §10) | `catalog.CatalogText.key/normalize`, `catalog.GroupCodes.canonical` (now also converts Latin look-alikes in initials of a Cyrillic name) | `timetable…importing.NameKeys` |
| Load pattern `pairs = hoursPerWeek / hoursPerPair` | `load.LoadPattern.of` | `LoadUnitExpander.pattern` |
| SSE after commit | `shared.sse.SseAfterCommit` | `timetable…support.AfterCommit` |

Added: `GET /api/me/schedule` (`timetable.web.MyScheduleController`, teacher's own schedule through
`ScheduleQueries`, 403 for an account without a linked teacher); the shared range rule of the public and
the teacher schedule is `timetable.web.ScheduleRange`.

Checked: Flyway versions are unique (V1_0xx…V1_8xx, demo V9_001), bean names and REST paths are unique
(the full application context starts in the ITs), `ModularityTests` (Spring Modulith `verify()`) and
`ArchitectureRulesTest` pass.

Left on purpose (dependency direction, not duplication of a public API):
- timetable reads `load_item`, `teacher_preference` and `change` through SQL read models, because load and
  substitution depend on timetable. `LoadReadRepository` could now use `load.LoadQueries` (load no longer
  depends on timetable); kept because the snapshot needs columns `LoadItemView` does not carry.
- `substitution.internal.support.CurrentTeacher` resolves the teacher entity of the caller by account
  (same rule as `identity.CurrentUserService.requireTeacherId()`, different 403 body).
- `CalendarQueries.isStudyDay/isGroupStudyDay` (per date, `PeriodRules`) and `StudyCalendar.day` (batch)
  implement the same DOMAIN §2 rule twice inside the calendar module; for a PRACTICE period with scope
  ALL the per-date college-wide check keeps the day while the batch view closes it.

## 3. Test data hygiene

Reference data (V1_201 bell profiles and shift windows, V1_802 setting defaults) now exists in every
database, and the ITs share one database. Test clean-ups no longer truncate `bell_profile`,
`shift_window` or `setting`; they call `support.ReferenceData.restore(jdbc)` (drops test bell profiles,
resets settings to defaults). Test fixtures create their own profile «Тест: 2 зміни, 60 хв».
`EventProbe` buffers events that do not match the current `await`: the outbox does not keep the order
in which one transaction published `order.published` and `schedule.changed`.

## 4. Web

- Mock API: every endpoint is registered once. Removed fallbacks: `/public/groups|teachers|rooms`,
  `/admin/shift-windows`, `/admin/disciplines` (timetable.ts), `/public/groups|teachers`,
  `/public/schedule/teacher/:id` (telegram.ts), `/public/bells`, `/import/ai/usage` (settings.ts), the
  versions fallback in load.ts, and the TEACHER-only `/notify/bindings/codes` handler in me.ts (telegram.ts
  handles every type).
- One hook per endpoint: `usePublicGroups/Teachers/Rooms`, `usePublicSchedule` (now with `{enabled}`),
  `useBellProfiles` (api/public), `useShiftWindows` (api/calendar), `useAiUsage` (api/import),
  `useCreateBindingCode` (api/telegram, also used by the teacher cabinet). `useBells(date)` (api/meta)
  stays as the dated variant of `/public/bells`.
- The catalog colour palette follows the redesigned five discipline lines of `styles/lines.css`.
- `OrderDto.subtitle` (core) is shown under the heading when present.
- Design-system fixes found by the merged e2e suite: the radio circle and the checkbox box sit above the
  label overlay (`after:inset-0` made the whole row clickable but covered the control itself), and the
  admin date navigator label truncates on a 360 px screen instead of pushing the header 16 px wide.
- e2e expectations updated to the redesign: the public lesson card prints the reason in words
  («Причина: взаємна заміна»); the load mock test no longer assumes a single version in the list.

## 5. Open mismatches between web and backend (for the wiring phase)

Paths of every SPA call exist in the backend. Shapes and codes that differ:

1. `GET /api/notify/deliveries` — notification returns a page `{items,page,size,total}`; web (`api/telegram.ts`,
   mock) expects `DeliveryDto[]`. Item fields also differ (`purpose`, `kind`, masked `chatId`, `nextAttemptAt`).
2. `GET /api/import/jobs` — importer returns a page; web (`useImportJobs`) expects `ImportJob[]`.
3. `POST /api/import/jobs` `options` — web sends `{groupIds}`; importer accepts only `sheet`, `versionName`,
   `semesterNumber`, `groupCodes`, `hoursPerWeek` and answers 400 on `options.groupIds`.
4. `POST /api/notify/bindings/codes` — 200 (mock 201); response adds `type`, `label`, `groupLink`, `command`
   (web type has only `code`, `deepLink`, `expiresAt`); a teacher without a linked account gets 422
   `teacher-not-linked`.
5. `ChangeDto` for SWAP — web reads `pairTo` as the pair the lesson came from; core sends `swapFromPair`
   (plus additive `approvalMode`, `originalLessonId`, `swapLessonId`, `printedReason`, `responseComment`,
   `remindedAt`, `createdBy`, `createdAt`, `version`; `PUT` needs `version`).
6. `OrderDto.title` — core: «РОЗПОРЯДЖЕННЯ №N»; the line under it is `subtitle` (handled), `dateLabel` is unused.
   The mock still puts the subtitle into `title`.
7. Dashboard alerts — core kinds `NO_SEMESTER`, `DECLINED`, `UNCOVERED_LESSONS`, `PENDING_OVERDUE`,
   `ORDER_NOT_PUBLISHED`; web/mock use `UNCOVERED_SOON`, `UNCOVERED`, `DISCIPLINE_FINISHED`, `WILL_UNDERRUN`,
   `WILL_OVERRUN` too (unknown kinds render by severity, so nothing breaks). `aiSpendToday` is always null.
8. `PublicMetaDto` — core may send `semester`, `weekNumber`, `parity`, `bellProfile` = null (no semester) and
   `nextPair` without `minutesLeft`; web types them as non-null.
9. `PublicGroupDto.department` may be null in core; web types it as `string` and prints it
   (`BindingCodeDialog`: «N курс, null»). `PublicTeacherDto` adds `fullName`.
10. Absences — deleting a started absence of a teacher: core 422 `ABSENCE_STARTED`; web expects
    `ABSENCE_NOT_FUTURE`. Core also has `ABSENCE_IN_PAST`, `ABSENCE_TEACHER_FIXED`, `ABSENCE_CANCELLED`,
    `ABSENCE_MISMATCH`, and 409 for overlaps.
11. Settings — core limits `confirmTimeoutMinutes` 5..1440 (default 120), signatory position ≤ 200 and name
    ≤ 100 characters; the web form and mock use 5..240 (default 15), 120 and 80.
12. Generation job — core adds `timeLimitSeconds`, `semesterId`, `baseVersionId`, `requestedBy`, `createdAt`;
    web reads the time limit from an optional `params` that core does not send.
13. `LessonView.reasonText` is now null for ADDITIONAL/BLOCK/ROOM_CHANGE lessons (same as the order).
    `startsAt/endsAt` are null without a bell profile; `weekdayLabel` is upper case («ПОНЕДІЛОК»).
14. SSE — core also sends `absence.updated` (substitution); web listens to `absence.created` only (web side).
15. Not available in the backend for web features: `maxParallel` in `/public/rooms`, deleting invites,
    `POST /api/admin/import/changes` (historic orders). Added on 08.11: own Telegram binding status
    `GET /api/notify/bindings/me`, linking/unlinking a teacher account `PUT/DELETE /api/admin/teachers/{id}/account`,
    order numbering `GET/PUT /api/admin/orders/numbering` (see `docs/RUNNING.md`).
16. Streams: `ProblemSnapshot` has no stream id, so the scheduler plans stream units separately; web and mock
    move lessons of one `streamId` together, core moves a single lesson.

### Status after the web wiring (08.11.2026)

Items 1–14 are closed on the web side: types, screens and the mock API follow the backend shapes and codes,
and `web/src/api/contract.test.ts` checks every mocked response against the Java records (details:
CHANGELOG-CONTRACTS, «Web: wiring to the backend contracts»).

### Status after the SPA ran against the real stack (08.11.2026)

`web/e2e-real` (opt-in Playwright suite, `npm run e2e:real`, docs/RUNNING.md §6) drives the SPA through the
gateway with real Keycloak logins. Closed in this pass (CHANGELOG-CONTRACTS, «SPA against the real stack»):
- `affected` items carry `date` (multi-day absences work on the changes screen).
- Item 15: `maxParallel` in public rooms; `GET /api/notify/bindings/me` wired into the teacher cabinet.
- Gateway answers 503 (not 500) when a stopped service's host does not resolve.

Still open:
- Item 15: deleting invites in core (historic order import `POST /api/admin/import/changes` exists since
  round 1 of the verified failures, CHANGELOG-CONTRACTS);
  `PUT/DELETE /api/admin/teachers/{id}/account` and `GET/PUT /api/admin/orders/numbering` have no SPA screens
  and no mock handlers yet (the seed uses them through the API).
- Item 16 (streams in the solver) is unchanged.
- With the bot switched off the changes screen still says «Чекаємо відповіді в Telegram»; the teacher can answer
  in the cabinet, but the text does not say so.

## 6. How to verify

```bash
source scripts/env.sh
mvn -q verify                                   # all modules, unit + Testcontainers ITs
cd web && npm ci && npm run build && npm run lint && npm test
PW_PORT=5291 npx playwright test                # a free port if 5180 is taken
```
