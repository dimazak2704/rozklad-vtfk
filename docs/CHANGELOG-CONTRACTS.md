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

## Timetable (core)

| Area | Decision | Why |
|---|---|---|
| Public schedule | `from`/`to` optional: default = current week (Monday–Sunday); `to < from` or more than 31 days → 400 `errors[{field:"to"}]`. `days[]` contain Monday–Friday only. | DayDto has `weekday (1..5)`. |
| `DayDto` | `weekdayLabel` in upper case («ПОНЕДІЛОК», «ПʼЯТНИЦЯ»), `parity` = `null` for a date outside every semester; `studyDay=false` on holidays, outside the semester and (group view) during the group's practice; `note` = titles of the periods that apply, joined with «; ». | Matches the web demo data; no invented parity. |
| `LessonView` | `startsAt`/`endsAt` = `null` when no bell profile exists. `original` = discipline, teacher and room of the change row plus `pair` when the lesson was moved (UI compares ids). A cancelled original is omitted when its replacement (same change) is in the same view; the absent teacher still sees it as CANCELLED. BLOCK `endsAt` = end of the last pair of the block. | One row per pair in a group view, diff-style rendering in the UI. |
| Public caching | `Cache-Control: no-cache, public` + strong ETag on `/api/public/schedule/**` and `/api/public/ics/**` (304 on `If-None-Match`). | Always fresh after a published change, no body when unchanged. |
| ICS | Current semester = active one (else the one containing today), SCHEDULED lessons only, UTC times, `UID: <lessonId>@rozklad.vtfk`; `room` → 404. | CONTRACTS lists group/teacher calendars only. |
| Versions list | Items add `semesterId`, `publishedBy`, `generationJobId`, `notes`, `createdAt`; without `semesterId` the active semester. | Editor needs them; additive. |
| New endpoints | `GET /api/admin/timetable/versions/{id}`; `DELETE /api/admin/timetable/versions/{id}` (draft only → 204, otherwise 409). | Managing generated drafts. |
| Lesson edits | `POST/PUT` → `{lesson, conflicts}` (201 + `Location` for POST); `DELETE` → 200 `{lesson:null, conflicts}`; `conflicts` = hard and soft conflicts of the lesson and of the touched slots. Lesson body: `{weekday, pair, parity, groupId, subgroup?=0 (0..9), disciplineId, teacherId?, roomId?, lessonKind?=MIXED, loadItemId?, pinned?, online?, streamId?}`. | Matches `TemplateLessonMutationResponse` of the web types. |
| Move | 409 only when the moved lesson itself gets a hard conflict (conflicts of other lessons in the target slot do not block); `?dryRun=true` answers with the same codes and saves nothing; `roomId` omitted = keep the room. | DnD semantics. |
| References in bodies | Unknown group/discipline/teacher/room/load item/semester/base version → 422 `REFERENCE_NOT_FOUND`; load item of another group/discipline/semester → 422 `LOAD_ITEM_MISMATCH`; editing a non-draft → 409. | Path ids stay 404; body references are business rules. |
| Publish | 200 `{versionId, effectiveFrom, materializedLessons, keptLessons, coveredByChanges, conflicts}`; 422 `EFFECTIVE_FROM_OUTSIDE_SEMESTER`, and `EFFECTIVE_FROM_IN_PAST` when a published version already exists and `effectiveFrom` < today (the first publication may start in the past). `conflicts` = HARD conflicts of kept change lessons with the new version (template occurrences blocked by them are not created). | DOMAIN §6 «kept and reported»; past lessons are history. |
| Import timetable | 201 + `Location`, body `{versionId, name, lessonCount, hardConflicts, softWarnings}`; ≤ 5000 lessons; unresolved or ambiguous names → 422 `IMPORT_UNRESOLVED_REFERENCES` with `errors[{field:"lessons[i].teacherShortName", message}]` (one entry per field and spelling, «(рядків: n)»); nothing is saved. | The importer can show exactly what to map. |
| Generation start | 202 `{jobId}` + `Location`; 409 while a job of the semester is QUEUED/RUNNING; 422 `GENERATION_EMPTY_SCOPE`, `GENERATION_NO_LOAD`, `GENERATION_SPLIT_WITHOUT_SUBGROUPS`, `GENERATION_BASE_VERSION_MISMATCH`. Scope = groupIds ∪ active groups of courses, all active groups when both empty; base = given version or the published one. Weights 0..1000. | Explicit, testable rules. |
| Generation job DTO | Adds `semesterId`, `createdAt`, `baseVersionId`, `timeLimitSeconds`, `requestedBy`; `progress[].t` in seconds (one decimal); `bestScore` = `"{hard}hard/{soft}soft"`; list without `semesterId` = latest 50 jobs. Cancel → 202 with the job (idempotent for CANCELLED, 409 for SOLVED/FAILED). | Live chart and history. |
| `ProblemSnapshot` | Unknown group size → `0`, unknown room capacity → `Integer.MAX_VALUE`; timeslots = union of shift windows; rooms = active rooms + rooms of pinned units; teacher `name` = short name; `unitId` = `{loadItemId}:{subgroup}:w{n}\|h` or `pin:{templateLessonId}`. Streams are not represented (the contract has no field). | The solver must not invent capacity violations. |
| Scheduler results | Payload type is chosen by routing key; unknown key or unreadable JSON → DLQ. Events of unknown or finished jobs are ignored; a SOLVED answer with an unknown/duplicate unit, an off-grid slot or a HALF unit without week parity marks the job FAILED. | No silent repair of a solver bug. |
| SSE | `generation.progress` = `{jobId, elapsedMs, hardScore, softScore}` and `generation.completed` = job DTO (ADMIN); `schedule.changed` = `{dates, groupIds, teacherIds, reason}` (everybody), after commit. | Data of the related DTO. |

## Substitutions, orders, accounting (core)

| Area | Decision | Why |
|---|---|---|
| `ChangeDto` | Additive: `approvalMode`, `originalLessonId`, `swapLessonId`, `swapFromPair`, `printedReason` (text of «Причина заміни»), `responseComment`, `remindedAt`, `createdBy`, `createdAt`, `version`. `new` is the JSON name of the replacement side. | The UI shows what the order prints and needs `version` for optimistic locking. |
| `PUT /api/admin/changes/{id}` | Body `{newRoomId?, reasonCode, reasonText?, version?}`: only room and reason. Another teacher or discipline = `DELETE` + `POST`. A stale `version` → 409 «Дані змінилися». | The teacher who was asked must get a clear «скасовано» from the bot. |
| `DELETE /api/admin/changes/{id}` | 204; repeating it is a no-op; an APPLIED change → 422 `CHANGE_ALREADY_APPLIED`. | Soft cancel, safe to retry. |
| Change transitions | Illegal transition → 422 `CHANGE_ILLEGAL_TRANSITION`; dispatcher confirmation of a DECLINED change → 422 `CHANGE_DECLINED`. Teacher answers via `/api/me/changes/{id}/confirm\|decline` behave like the bot reply: 403 not the assigned teacher, 409 not pending. | One rule for both ways of answering. |
| `POST /api/admin/changes` | 400 `errors[]` for the fields each kind needs (e.g. `newTeacherId`), 409 `conflicts[]` for busy slots, 409 when the lesson already has an open decision, 422 for a lesson/date mismatch, weekends, dates outside a semester or without classes for the group. | Field-level messages for the form; conflicts as in the timetable editor. |
| `Option` | Additive `date`, `pair` (ADDITIONAL make-up lesson). REPLACE_DISCIPLINE/SWAP/CANCEL/ADDITIONAL scores use documented base values (`docs/modules/substitution-order-accounting.md` §4). | One-click make-up; one ranked list across kinds. |
| `OrderDto` | Additive `subtitle` («По коледжу про деякі зміни в розкладі занять»), `dateLabel` («На 19 жовтня 2026 р.»), `publishedAt`, `publishedBy`, `version`. `title`: «РОЗПОРЯДЖЕННЯ» (draft), «РОЗПОРЯДЖЕННЯ №35», «ДОПОВНЕННЯ до розпорядження №35» (amendment; its own number is `number`). | Every client prints the same header; `OrderPayload` has no parent number, so the amendment title carries it. |
| Order rows | Printed text is stored in the row: CANCELLATION `toDiscipline` = «Пари не буде»; ADDITIONAL `fromDiscipline` = «ДОДАТКОВО»; BLOCK discipline/teacher on the `from` side; AS_SCHEDULED `fromDiscipline` = «За розкладом» (generated for a block's other pairs); «(п.)» after a single subgroup's teacher; subgroups merged with «/». | The row is the document of record (DOMAIN §3); renderers print it as is. |
| `GET /api/admin/orders/draft` | When the order of the date is published and nothing new is confirmed, returns the latest published document (no empty amendment is created). | A GET must not litter drafts. |
| `POST /api/admin/orders/{id}/publish` | 409 when the draft no longer matches the confirmed changes («Чернетка застаріла») or when applying creates conflicts (`conflicts[]`, nothing is published); 422 `ORDER_ALREADY_PUBLISHED`, `AMENDMENT_EMPTY`. | Only what the dispatcher has seen gets a number. |
| `GET /api/admin/orders` | Page of `{id, number, date, kind, status, itemCount, publishedAt}`; `q` = order number or a fragment of a group code / teacher name in the rows. | — |
| `GET /api/public/changes` | Only published orders (main + amendments). `date` defaults to today; `parity` is `null` outside every semester; `weekdayLabel` «Понеділок»; `subgroupNote` «1 підгрупа»; a row of a group missing in the catalog has `group.id = null`. `ChangeView.from/to` are text. | Unpublished decisions are not official. |
| `GET /api/admin/absences` | Array (not a page) of active absences overlapping `[from, to]` (default: today … +31 days, at most a year). `AbsenceDto` adds `createdBy`, `createdAt`, `version`; PUT accepts `version`; the teacher of an absence cannot change (422). Overlapping absences of one teacher → 409. | — |
| `POST /api/internal/absences` | `source` optional (BOT); any other value → 422 `ABSENCE_SOURCE`. | — |
| `/api/me/*` | An account without a linked teacher gets 403. `/api/me/preferences` uses the active semester when `semesterId` is omitted. Teachers cancel only absences that have not started (422 `ABSENCE_STARTED`). | DOMAIN: own data only, future only. |
| SSE | `change.updated` = `ChangeDto`; `absence.created` and the new `absence.updated` = `{id, teacher, dateFrom, dateTo, pairs, source, status}` (no reason/comment); `order.published` = order summary; `schedule.changed` = the event JSON. | Private absence data never leaves the REST API. |
| `substitution.confirmation-requested` | A reminder repeats the event with the same `changeId` and the original, already passed `deadline` (once per confirmation round). Transitions into PENDING publish this event instead of `substitution.status-changed`. `schedule.changed` (ORDER_PUBLISHED) is published only when a publication applied changes. | No new event type in the contracts library. |
| Dashboard | `aiSpendToday` is always `null`; `alerts[]` = `{kind: NO_SEMESTER\|DECLINED\|UNCOVERED_LESSONS\|PENDING_OVERDUE\|ORDER_NOT_PUBLISHED, severity: INFO\|WARN\|ERROR, message, link}`. | AI usage lives in the importer. |
| Accounting | Rows only for group × discipline with load items. `suggestion` = `{kind: DISCIPLINE_FINISHED\|ADDITIONAL, text, fromDate?, lessons, lessonIds}`. Teacher statistics list only teachers with activity in the month. | Accounting needs a plan; `lessonIds` make the one-click action possible. |
| Settings keys | `substitution.approvalMode`, `substitution.confirmTimeoutMinutes`, `order.signatoryPosition`, `order.signatoryName`, `accounting.hoursPerPair` (defaults in the module doc). | To be aligned with the settings module. |

## Web: public site and teacher cabinet (client-side interpretations)

No endpoint was changed. These are the readings the SPA and its mock API use where CONTRACTS.md is silent.

| Area | Decision | Why |
|---|---|---|
| `GET /api/public/changes` | Rows (`groups`) come only from a published order; before publication `order: null`, `groups: []`. | DOMAIN §7: changes enter the schedule on publication, so the changes page and the group schedule never disagree. |
| `ChangeView.from/to.teacher` | Order-style text: surname, plus first initial for namesakes (`TeacherRef.displayName`, «Крисак Л.»). | The «лише мої» filter matches surname and initials. |
| `ChangeView.subgroupNote` | Human text «1 підгрупа» (null = whole group). | Shown as is; the paper «(п.)» is unclear to students. |
| `ChangeView` for `ROOM_CHANGE` | `from` = the lesson, `to` empty, `room` = the new room, `reasonText` null. | The «Заміна аудиторій» section prints only group, pair and room. |
| `GET /api/public/bells?date=` on a weekend | `slots: []`, `weekday` = ISO weekday (6/7). | No bells on weekends; the SPA never asks (weekend → Monday). |
| `PUT /api/me/preferences?semesterId` | Body is the full list `[{weekday,pair,kind,note}]` replacing the previous one; response = saved list. | The grid is saved as a whole in one action. |
| `POST /api/me/absences` | Past `dateFrom` → 422 (`ABSENCE_IN_PAST`); overlap with an existing absence → 409. `DELETE` of a non-future absence → 422 (`ABSENCE_NOT_FUTURE`). | «Own only, future only» (CONTRACTS §3); 422 for business rules as in the foundation. |
| `POST /api/notify/bindings/codes` by a teacher | Only `{type: TEACHER, teacherId: <own>}`; another `teacherId` → 403. `deepLink` = `https://t.me/<bot>?start=<code>`. | CONTRACTS §6: «TEACHER may create own TEACHER code». |
