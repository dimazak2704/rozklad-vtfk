# Contract deviations and clarifications

Every agent appends its own section (ARCHITECTURE §14): what differs from `CONTRACTS.md` /
`DOMAIN.md` and why. Additive clarifications are listed too, so clients know the exact shape.

## Foundation (events, errors, schema)

| Area | Decision | Why |
|---|---|---|
| Event JSON | Envelope fields `eventId`, `occurredAt`, `version` are top-level fields of every event record (no nested envelope object). | Matches "every payload has eventId, occurredAt, version" and keeps consumers simple. |
| `order.published` | `{eventId, occurredAt, version, order: OrderPayload}` — the order is nested under `order`. | `OrderPayload` is reused unchanged as the body of `POST /api/documents/orders/render`. |
| `generation.cancel` | Java record is `GenerationCancelRequested` (routing key unchanged). | A record name should state what happened. |
| `schedule.changed.reason` | Enum `ScheduleChangeReason {VERSION_PUBLISHED, ORDER_PUBLISHED, CALENDAR_CHANGED, LESSONS_EDITED}`. | The contract gave no type; an enum is checked at compile time. Append values only. |
| `ProblemSnapshot.units[].unitId` | Opaque `String`, unique within one snapshot (not a UUID). | Units are derived (load item × subgroup × index), not stored entities. |
| Scores | `hardScore`/`softScore` are `long`; `analysis[].score` is the solver's formatted score string (`0hard/-35soft`). | Covers int and long score types of Timefold; the string is for display only. |
| `GenerationRequested.weights` | `null` is replaced by `GenerationWeights.DEFAULT` (10, 10, 3, 5, 1). | `weights?` is optional in the REST request. |
| `absence.reported.pairs` | `null` = whole day (kept as `null` in JSON). Reason category and comment are never in the event. | DOMAIN §7: absence reasons are private. |
| Times in JSON | `LocalTime` is written as `HH:mm` by services; events between services use the same mapper. Reading accepts `HH:mm` and `HH:mm:ss`. | CONTRACTS: times `HH:mm`. |
| `Conflict.kind` | Adds soft kinds `WINDOW, BROKEN_DAY, DAY_OVERLOAD, DISCIPLINE_REPEAT, UNWANTED` to the hard kinds. Null `date`/`weekday` are omitted. | `GET …/conflicts` returns `soft[]` (DOMAIN §5) that need kinds too. |
| ProblemDetail | `type` = `urn:rozklad:problem:<slug>` (`validation`, `not-found`, `conflict`, `schedule-conflict`, `stale-data`, `data-integrity`, `business-rule`, `unauthorized`, `forbidden`, `internal`, gateway: `service-unavailable`, `gateway-timeout`); extra members `instance`, `requestId`; 422 has `code`. | Stable machine-readable types; `requestId` links a user report to logs. |
| Validation `errors[]` | Sorted by `field`; nested fields use dots/indexes (`items[0].name`); request parameters use the parameter name. | Deterministic output for the UI. |
| Lesson uniqueness | DOMAIN's partial unique indexes are implemented as deferrable `EXCLUDE USING btree … WHERE (status = 'SCHEDULED' …)` constraints with the same names; checked at commit or via `LessonRepository.checkSlotConstraintsNow()`. | Same guarantee, but applying a change / SWAP inside one transaction works despite Hibernate's INSERT-before-UPDATE flush order. |
| Extra DB guards | One active semester; one PUBLISHED version per semester; one main ORDER per semester and date; ORDER ⇔ no parent; PUBLISHED ⇒ number; lessons Mon–Fri; parity overrides on Mondays; course 1..6; shift 1/2; pair 1..8; weekday 1..5. | Invariants of DOMAIN made explicit in the schema. |
| Nullable columns | `room.capacity`, `student_group.size`, `student_group.number`, `specialty.code`, `specialty.department_id`, `teacher.first_name`, `teacher.middle_name`. | Imported data (Excel) does not have these values; DOMAIN forbids inventing them. |
| Child tables with `id` | `teacher_discipline`, `calendar_period_group`, `load_item_teacher` keep an `id uuid` PK plus a unique natural key. | DOMAIN §3: "All tables: id uuid pk". |
| notification path | `POST /api/notify/telegram/webhook` is reserved and open (secret header checked by the controller). | Needed for WEBHOOK mode (ARCHITECTURE §6); not listed in CONTRACTS. |
| Gateway | Adds no `X-Forwarded-*` headers (no trusted proxy); preserves `Host`; own errors are ProblemDetail. | Forgery-safe defaults; correct absolute `Location` headers. |

## Catalog, calendar, load, identity, settings (core)

Details and DTO shapes: `docs/modules/catalog.md`, `calendar.md`, `load.md`, `identity.md`, `settings.md`.

| Area | Decision | Why |
|---|---|---|
| Paged lists | `page` ≥ 0, `size` 1..200 (default 20), repeated `sort=field[,asc\|desc]` with a per-resource whitelist; anything else is a 400 on `page`/`size`/`sort` (no silent clamping). A stable `id` order is appended. | Explicit errors instead of surprising pages. |
| Catalog DTOs | Fields of DOMAIN §3 plus refs: specialty `department: Ref`; teacher `cycleCommission: Ref`, `linked`; room `building: Ref`; group `specialtyId`, `specialty: Ref (label = letters)`, `curator: TeacherRef`, `homeRoom: RoomRef`, `subgroups[]`. Every mutable DTO has `version`; a `PUT` may send it → 409 `stale-data` when outdated. | CONTRACTS lists the CRUD paths only. |
| Competences | `/api/admin/teachers/{id}/disciplines`: `GET`, `PUT {disciplineIds}` (replace), `POST {disciplineId}`, `DELETE /{disciplineId}` → `[DisciplineRef]`. Aliases: `GET`, `POST {alias}` (201), `DELETE /{aliasId}`. | Shapes were not specified. |
| Delete | 409 `conflict` with a message naming the record when the database reports a reference (also other modules' rows); subgroups ask the modules that store subgroup numbers. | DOMAIN: «204 or 409 if referenced». |
| Import catalog | Additive sections `cycleCommissions`, `buildings`; items use natural keys (see catalog.md); result adds `unchanged`, `warnings`; `ids` per section keyed by the key as sent. All-or-nothing: 400 with `errors[]` (`groups[2].code`). | CONTRACTS gave only section names. |
| Import load | Items `{groupCode, disciplineShortName, hoursPerWeek, plannedHours?, lessonKind?, splitSubgroups?, teachers:[{subgroup, teacherShortName}], controlForm?, requiredRoomType?, preferredRoomName?, source, note?}`; `source` required; result `{created, updated, unchanged, ids}`. | Same style as the timetable import (natural keys). |
| Load list | `semesterId` is required; `LoadItemDto` adds `streamId`, `note`, `version`. | No implicit "current semester". |
| Derive load | `POST …/derive-from-version/{id}` returns `{versionId, semesterId, created, updated, removed, skipped[], items[]}` instead of a bare list. | The admin must see what was skipped (manual load wins) and removed. |
| Public search | Room labels «ауд. 6» for numbered rooms; `url` percent-encodes the group code like `encodeURIComponent` (`/g/4%D0%9A%D0%861`); `limit` 1..50; blank `q` → `[]`. | Same routes and encoding as `web/src/app/routes.ts`. |
| Public lists | Active records only; teachers add `fullName` (only when `showTeacherFullNames`); `department` of a group may be `null`. | Short names are the public default (UI.md). |
| Public meta | `semester`, `weekNumber`, `parity`, `bellProfile` may be `null` (no semester, or today between semesters); `nextPair` has no `minutesLeft`. | Never invent a parity outside a semester. |
| Public bells | `weekday` is ISO 1..7 (weekends → empty `slots`); adds `date`, `studyDay`; `profiles` = active profiles with slots, only without `date`; 404 when no profile is configured. | Weekends and holidays need an honest answer. |
| Calendar admin | Semester DTO adds `academicYear`, `teachingWeeks`, `bellProfile: Ref`; request `academicYear` and `name` optional (derived). Weeks: `{weekStart, weekEnd, weekNumber, parity, computedParity, overridden}`; `PUT /weeks/{weekStart} {parity}` (`null` clears) plus additive `DELETE /weeks/{weekStart}`. Bell profile `{id, name, active, defaultProfile, slots:[{weekday, pair, startsAt, endsAt}], version}`. Period adds `groups: [GroupRef]`, `version`. Shift windows: PUT takes the whole array of 10 rows. | Shapes were not specified. |
| Calendar events | Parity overrides, calendar periods and semester date / first-week-parity edits publish `schedule.changed` with reason `CALENDAR_CHANGED` (affected Mon–Fri dates and groups). | Caches and materialized lessons depend on the calendar. |
| Invites | `POST` response adds `id`, `teacher`; list items `{id, teacher, status, expiresAt, usedAt, revokedAt, createdAt, createdBy}` (no URL — only the hash is stored); public `GET` adds `status` (ACTIVE/USED/EXPIRED/REVOKED) and is 404 for an unknown token; `accept` also answers 400 (validation), 404 and 503 (`identity-unavailable`); the 410 body (`invite-gone`) carries `status`. A newer invite revokes older ones. | One-time links that only the newest invite can use. |
| `GET /api/me` | `roles` sorted; `teacher` is `null` when the account is not linked. | — |
| Internal | Snapshot adds `active` flags and `specialties`; `semesters` are admin `SemesterDto`s; it is served by the calendar module. `GET /api/internal/teachers/{id}` → `{id, lastName, firstName, middleName, shortName, displayName, cycleCommission, active, keycloakUserId, linked}`. | Importer needs specialties to create groups. |
| Business errors | 422 `DATE_OUTSIDE_SEMESTER` (`CalendarQueries.parity/weekNumber` for a date outside every semester), 422 `TEACHER_INACTIVE` (invite for an inactive teacher). | Stable codes for clients. |
| Settings | `PUT /api/admin/settings` replaces all values; ranges `confirmTimeoutMinutes` 5..1440, `hoursPerPair` 1..4; the default bell profile must exist and be active. | DOMAIN §7–§9. |
