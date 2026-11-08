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
| `POST /api/me/absences` | *Superseded by «Web: wiring to the backend contracts».* As core: an absence that already ended (`dateTo` < today) → 422 `ABSENCE_IN_PAST`; overlap → 409. `DELETE` of a started absence → 422 `ABSENCE_STARTED` (the record stays with status `CANCELLED`). | Core is the source of truth. |
| `POST /api/notify/bindings/codes` by a teacher | Only `{type: TEACHER, teacherId: <own>}`; another `teacherId` → 403; an account without a linked teacher → 422 `teacher-not-linked`. `deepLink` = `https://t.me/<bot>?start=<code without dash>`. | CONTRACTS §6: «TEACHER may create own TEACHER code». |

## admin-daily (SPA: dashboard, changes, orders, Telegram, settings)

| Area | Decision | Why |
|---|---|---|
| `GET /api/admin/absences/{id}/affected` | **Still requested from core:** each item also has `date` (ISO): `{date, lesson, decision}`. Core does not send it yet; the client derives the date only for a one-day absence and otherwise shows an explicit error (`withLessonDates`). | An absence can span several days and `LessonView` has no date; the UI groups by date and sends it in `POST /changes`. |
| `GET /api/admin/lessons/{id}/options` | As core: `{lesson, options}` without a top-level `date`; an `ADDITIONAL` option carries `date` and `pair` of the make-up slot (`null` for other kinds). | Core shape. |
| `ChangeDto` for `SWAP` | As core: `pairTo` = `pairFrom` (range end, not the source pair); the source pair is `swapFromPair`. | Core shape. |
| `GET /api/admin/changes?date` · `GET /api/admin/absences?from&to` | Plain arrays (not `Page`); `absences` lists `ACTIVE` ones overlapping the range. | Same in core. |
| `DELETE /api/admin/absences/{id}` | Sets `CANCELLED` (repeating is a no-op); its not-yet-applied changes become `CANCELLED`; SSE `absence.updated`. | Same in core. |
| `GET /api/notify/deliveries` | *Superseded:* page `{items,page,size,total}` as in notification. | Notification is the source of truth. |
| `DashboardAlert.kind` | *Superseded:* exactly the core kinds `NO_SEMESTER`, `DECLINED`, `UNCOVERED_LESSONS`, `PENDING_OVERDUE`, `ORDER_NOT_PUBLISHED`; unknown future kinds are shown by severity. | Core shape. |
| Order heading | The sheet prints «РОЗПОРЯДЖЕННЯ №N» / «ДОПОВНЕННЯ №N до розпорядження №M» from `kind`, `number` and the parent's number, then core's `subtitle` and `dateLabel`. Core's `title` («РОЗПОРЯДЖЕННЯ №35», «ДОПОВНЕННЯ до розпорядження №35») is kept as is in the mock. | DOMAIN §8 layout; the amendment's own number is not in core's title. |


## Web: wiring to the backend contracts (08.11.2026)

The SPA types (`web/src/api/types.ts`), screens and the mock API now follow the backend DTOs; the open
mismatches of `docs/modules/integration.md` §5 are closed on the web side. `web/src/api/contract.test.ts`
reads the Java records in `services/*` and fails when a mock response has a field the DTO does not have or
misses one it has (`@JsonInclude(NON_NULL)` fields may be absent).

| Area | Client behaviour | Why |
|---|---|---|
| Pages | `GET /api/notify/deliveries` and `GET /api/import/jobs` are read as `Page<T>`; both tables page on the server (deliveries 12 per page, jobs 20). The order delivery summary asks for one page of 200 and says when it counted only part of the log. | Backend shape; no unbounded lists. |
| `POST /api/import/jobs` `options` | Only `sheet`, `versionName`, `semesterNumber`, `groupCodes`, `hoursPerWeek`; the upload form sends group codes, a required plan semester for CURRICULUM, an optional draft name (timetable) and weekly hours (program). | Unknown keys are 400 in the importer. |
| `ImportJobDto` | `NON_NULL` fields are optional in the type; `canApply` decides the apply button; `result` is shown per core step (`catalog`, `timetable`, `load`, `changes`; `{skipped:true}` hidden). Apply errors are 422 `IMPORT_NEEDS_REVIEW`, `IMPORT_FAILED`, `SEMESTER_REQUIRED`, `GROUPS_REQUIRED`; repeating apply is idempotent. | Importer shape. |
| Binding codes | 200; the dialog uses `label`, `groupLink` (group chats) and `command`; the code is shown as `XXXX-XXXX`. The teacher cabinet shows 422 `teacher-not-linked` inside the card instead of a link. | Notification shape. |
| `ChangeDto` | All additive fields typed; `swapFromPair` for SWAP; `printedReason` shown on the decision card. «Змінити причину» sends `PUT {newRoomId (current), reasonCode, reasonText, version}`; 409 `stale-data` → «Дані вже змінив хтось інший…» and the day is refetched. A DECLINED change offers «Надіслати запит ще раз» (core answers 422 `CHANGE_DECLINED` to a dispatcher confirmation). | Optimistic locking and core transitions. |
| `AbsenceDto` | `comment`, `createdBy`, `createdAt`, `version` typed; the dispatcher's `PUT` sends `version` (409 `stale-data` handled like changes). The teacher cabinet hides `CANCELLED` absences (core lists them). Comments up to 1000 characters. | Core shape. |
| Business-rule codes | `ApiError.code`; Ukrainian texts for `ABSENCE_STARTED`, `ABSENCE_IN_PAST`, `ABSENCE_TEACHER_FIXED`, `ABSENCE_CANCELLED`, `ABSENCE_MISMATCH`, `teacher-not-linked` (`PROBLEM_CODE_MESSAGES`); other codes keep the server `detail`. `ApiError.isStaleData` tells optimistic-lock 409 from overlap 409. | One wording in the cabinet and the panel. |
| Settings | `confirmTimeoutMinutes` 5..1440 (default 120), signatory position ≤ 200, name ≤ 100, college name 1..200 (`api/settings-limits.ts`, shared by the form and the mock). | `SystemSettings` limits. |
| Generation | The panel reads `timeLimitSeconds` from the job (no `params`); cancel returns the job (202). | Core shape. |
| Timetable versions | Version items carry `semesterId`, `publishedBy`, `generationJobId`, `notes`, `createdAt`; publish shows `keptLessons` and warns when core reports `conflicts` of kept changes. | Core shape. |
| Public nulls | `PublicMetaDto.semester/weekNumber/parity/bellProfile`, `DayDto.parity`, `DashboardDto.parity`, `PublicChangesDto.parity`, `LessonView.startsAt/endsAt`, `PublicGroupDto.department` may be `null`: no parity badge, «Навчального семестру зараз немає», no lesson time, no department — never the text «null». A public change row may have `group.id = null`. `PublicTeacherDto.fullName` is shown on the teacher page when present. | Core never invents parity or times. |
| `weekdayLabel` | Day headers are formatted from `date`; the upper-case `DayDto.weekdayLabel` («ПОНЕДІЛОК») is not printed as is. | Core casing. |
| SSE | `absence.updated` (and `absence.created`) payload is the summary without reason/comment; both refresh absences, the dashboard, options and the cabinet. `order.published` is the order summary. | Core events. |
| `GET /api/public/bells` | Without `date` core answers for today (ISO weekday, empty slots on weekends) plus active `profiles`; the client only reads `profiles` from it. | Core shape. |

## Scheduler (copied from docs/modules/scheduler.md §9; no change to `libs/contracts`)

| Area | Decision | Why |
|---|---|---|
| `generation.completed.analysis` | All 15 constraints, always in the same order (hard first); `constraint` is the Ukrainian title, ids stay internal; `score` is `HardSoftScore.toString()` with weights applied (a constraint switched off by weight 0 shows its matches with `0hard/0soft`). | Stable UI table; Timefold's `ScoreAnalysis` is Enterprise-only. |
| CANCELLED | May carry `assignments` (best timetable before the cancel); none when the job was still queued or not every unit was placed. `hardScore`/`softScore` are 0 for FAILED and for CANCELLED without assignments. | Core can offer «apply anyway». |
| SOLVED | Can still have `hardScore < 0` (infeasible data); the analysis shows which constraints are broken. | Honest result instead of a failure. |
| Snapshot validation | Strict: unknown group/teacher/room references, a subgroup the group does not have, a shift without `shiftWindows`, a pin outside the timeslots, duplicate ids, no rooms, `timeLimitSeconds` above `app.scheduler.max-time-limit` → FAILED with a Ukrainian list of all problems. | No silent repair of bad input. |
| `generation.progress` | Only for complete timetables, at most every 2 s and only when the score changed; `elapsedMs` = solving time when that score was found. Best effort: may arrive after `generation.completed` (core ignores progress of finished jobs). | Fewer events, meaningful chart. |
| Pinned units | Without a room they come back with `roomId: null` and occupy no room. | Online/external lessons. |
| Streams | Not modelled (no stream id in `ProblemSnapshot`): two units of a stream count as a teacher conflict. | Contract gap, see Integration below. |

## Importer (copied from docs/modules/importer.md)

| Area | Decision | Why |
|---|---|---|
| `ImportJobDto` | Additive `fileType`, `fileSize`, `updatedAt`, `createdBy`, `semesterId`, `options`, `mappings`, `result` (core results per apply step), `canApply`; `ReportRow` adds a Ukrainian `message`. | The review screen needs them. |
| `GET /api/import/jobs` | Paged `{items,page,size,total}` (ARCHITECTURE §9), newest first; list items leave out `draft`/`options`/`mappings`/`result` and have an empty `report`. | Lists stay small. |
| `POST /api/import/jobs/{id}/apply` | Optional body `{semesterId}` overrides the semester given at upload; asynchronous 202. | Choose the semester at apply time. |
| Upload `options` | Keys `sheet`, `versionName`, `semesterNumber`, `groupCodes`, `hoursPerWeek`; unknown key → 400 on `options.<key>`. | Explicit errors. |
| `parse-absence` / `ai/usage` | `parse-absence` adds `source` (`RULES`\|`AI`); `ai/usage` adds `remainingUsd`, `enabled`. | Bot and settings page show where the answer came from and the budget left. |
| Errors | ProblemDetail types `ai-budget-exceeded` (429, `spentUsd`, `dailyBudgetUsd`), `unsupported-file` (415), `payload-too-large` (413), `service-unavailable` (503); 422 codes `IMPORT_NEEDS_REVIEW`, `IMPORT_FAILED`, `SEMESTER_REQUIRED`, `GROUPS_REQUIRED`, `AI_DISABLED`. | Stable codes for the UI. |
| Calls to core | `/api/admin/import/*` with header `Idempotency-Key: import-job:<id>:<step>`; element shapes in docs/modules/importer.md §8. | Safe retries. |

## Notification (copied from docs/modules/notification.md §9)

| Area | Decision | Why |
|---|---|---|
| `POST /api/notify/bindings/codes` | 200 (not 201): codes are not addressable resources. Adds `type`, `label`, `groupLink` (`?startgroup=`), `command` (`/bind …`). TEACHER role: teacher taken from `GET /api/me` with the caller's token; 422 `teacher-not-linked`. | Admins need the group-chat way to bind; ownership must not trust the body. |
| `GET /api/notify/bindings` | adds `groupCode`, `digestEnabled`; `chatId` masked `-•••7890` | Readable list without exposing chat ids. |
| `GET /api/notify/bot` | adds `problem` (Ukrainian reason when `connected=false`) | Admin sees why the bot is down. |
| `GET /api/notify/deliveries` | paged `{items,page,size,total}`; item `{id, orderId, chatId, chatTitle, bindingType, purpose, kind, status, attempts, error, createdAt, sentAt, nextAttemptAt}`; statuses `QUEUED/RETRYING/SENT/FAILED` | The contract only says «delivery log»; matches the SPA's inferred `DeliveryDto` plus extras. |
| `POST /api/notify/test` | 200 with the delivery; 503/422/502 | Synchronous: the admin wants to see the result. |
| `GET /api/documents/orders/{id}.{fmt}` | only orders received via `order.published`; ETag + 5 min cache; unknown format → 404 | Drafts are never public; core offers no public order payload. |
| `POST …/render` | `format` default `pdf`; invalid → 400 `errors[format]` | — |
| Webhook | `POST /api/notify/telegram/webhook` 404 unless WEBHOOK mode | Reserved path from the foundation. |

## Integration (reconciled behaviour, see docs/modules/integration.md)

| Area | Decision | Why |
|---|---|---|
| `GET /api/me/schedule?from&to` | Implemented: the signed-in teacher's `ScheduleDto`; same defaults (current week) and 31-day limit as the public schedule; 403 without a linked teacher. | CONTRACTS §3; was unmapped after the parallel work. |
| `LessonView.reasonText` | The order's printed reason (`ReasonCode.printed`): none for ADDITIONAL, BLOCK and room-only changes, «Вичит.» for a finished discipline, free text for OTHER. | One rule for orders and schedules. |
| Bell profile in effect | Everywhere: the semester's profile, else setting `calendar.activeBellProfileId`. | Timetable, substitution and accounting each had another fallback. |
| Confirmation deadline | `substitution.confirmTimeoutMinutes` from the settings (default 120 min, V1_802); the substitution module's own default of 60 min is gone. | One settings source. |

## Running stack and real data (08.11.2026, see docs/RUNNING.md)

| Area | Decision | Why |
|---|---|---|
| `GET/PUT/DELETE /api/admin/teachers/{id}/account` | New (identity): link a teacher to an account that already exists in Keycloak by `username`, or remove the link. Grants TEACHER, revokes open invites; never creates or deletes users. 404 teacher, 422 `ACCOUNT_NOT_FOUND`/`ACCOUNT_DISABLED`, 409 account of another teacher or the teacher has another live account, 503 Keycloak down. | The demo accounts of the realm (and accounts made in the admin console) could only be linked by the `demo` profile; invites create new users only. |
| `GET/PUT /api/admin/orders/numbering` | New (order, table `order_numbering`, V1_602): first order number of a semester; next number = max(last + 1, first). Setting it at or below the last used number is 422 `ORDER_NUMBER_USED`. | The college starts the system mid-semester (orders №1–34 on paper, №35 on 19.10.2026 in the system). |
| `GET /api/notify/bindings/me` | New (notification): own Telegram binding of the signed-in teacher; teacher resolved via core `GET /api/me` with the caller's token. | The teacher cabinet showed the status without an endpoint (integration §5 item 15). |
| Online lessons | A lesson that is online (template flag or STUDY_MODE ONLINE day) does not occupy its room: no room/capacity/room-type conflicts, dated lesson `room_exclusive = false`. | Order №35 gives room 403 to 1ОП2 while 3ОБ studies online there. |
| Import alias payload | importer sends catalog aliases as `{type, alias, target}` (core `AliasItem`), was `targetShortName` → core 400. | Contract mismatch found on the first live import. |
| Order document header | notification prints «По коледжу про деякі зміни в розкладі занять» under the heading; `OrderPayload.title` (= «РОЗПОРЯДЖЕННЯ №35») is not printed again. | The printed sheet repeated the heading instead of the college subtitle. |
| Keycloak client `rozklad-core-admin` | default client scope `kc-admin-api` (realm files + `keycloak-sync.sh`). | Keycloak 26.8 answers 401 to admin REST calls whose token lacks the admin API audience: invites and account links failed with 503. |

## SPA against the real stack (08.11.2026, see `web/e2e-real`, docs/RUNNING.md §6)

| Area | Decision | Why |
|---|---|---|
| `GET /api/admin/absences/{id}/affected` | Additive `date` per item (the lesson's date). The SPA reads it instead of deriving it; the fallback that refused multi-day absences is gone. | `LessonView` has no date and an absence may span several days: the changes screen showed an error for every multi-day absence. |
| `GET /api/public/rooms` | Additive `maxParallel` (how many groups at once; sports hall «с.з.» > 1). The room page prints «до N груп одночасно». | integration §5 item 15. |
| `PUT /api/admin/settings` errors | Field messages say what to enter («Від 5 до 1440 хвилин», «Вкажіть посаду підписанта», «Не довше 200 символів») instead of the validator's defaults. | They are shown next to the form fields. |
| Gateway: service down | A stopped container whose host name no longer resolves (`UnknownHostException`) and `NoRouteToHostException` are 503 `service-unavailable` like a refused connection; was 500 `internal`. | Docker removes the DNS name of a stopped container; clients retry 503 but not a generic 500 message. |
| `GET /api/notify/bindings/me` | Used by the teacher cabinet (Telegram card): bound → the chat and since when; not bound → the binding link; 422 `teacher-not-linked` → the explanation, without offering a link. | Was added on 08.11 but not wired. |


## Verified failures, round 1 (08.11.2026)

| Area | Decision | Why |
|---|---|---|
| Timetable publication | Lessons referenced by a live change as its original **or** as the lesson a SWAP moves are kept; a CANCELLED change pins nothing (V1_502: `change.original_lesson_id` / `swap_lesson_id` are `ON DELETE SET NULL`). | A SWAP after `effectiveFrom` blocked republication with a 409 data-integrity error; a cancelled change kept an old-version lesson scheduled. |
| `LessonView.original.pair` for SWAP | The pair of the moved lesson («перенесено з 3 пари»); the vacated cancelled lesson carries the change and is hidden next to its replacement, like a replaced original. | `original.pair` was null for every SWAP. |
| `POST /api/admin/changes` | A newly assigned teacher who is absent at (date, pair) is a hard conflict: 409 `schedule-conflict`, `conflicts[{kind: UNAVAILABLE}]`. Decisions about one date are serialized (advisory lock), so two parallel requests cannot promise one teacher or room twice. ADDITIONAL/BLOCK may name only a subgroup the group has (catalog or timetable), else 400 `errors[subgroup]`. | DOMAIN §7; races and a missing check found by the business pass. |
| `PUT /api/admin/changes/{id}` | `version` is required: without it 400 `errors[version]`, stale 409 `stale-data`. | integration §5 item 5 said «PUT needs version»; the server skipped locking without it. |
| Absences | Writes of one teacher are serialized (advisory lock): parallel overlapping absences give one 201 and 409 for the rest. | The overlap check was read-then-insert. |
| `POST /api/admin/import/changes` | New (order): `{date, number, kind?, parentNumber?, weekdayLabel?, parityLabel?, items:[{section?, groupCode, pairLabel, rowType, fromDiscipline?, fromTeacher?, reasonText?, toDiscipline?, toTeacher?, roomText?}], notes?, signatoryPosition?, signatoryName?}` → 200 `{orderId, number, date, kind, itemCount, created}`. Stores a paper order as a PUBLISHED order of record (text only: no change, no lesson, no `order.published`); the number counts in the semester numbering. Same number + date + rows again → `created: false`; same number with other rows or on another date → 409; future date → 422 `ORDER_IMPORT_FUTURE_DATE`; amendment without its parent → 422 `ORDER_PARENT_NOT_FOUND`. Public changes accept rows whose group is not in the catalog (`group.id = null`). | The importer's ORDER_DOCUMENT apply ended in 404 after the AI parse was paid for (integration §5 item 15). |
| Unbindable input (core, importer, notification) | A body value of the wrong type/format (unknown enum, impossible date, string for a list), a query parameter that does not convert, a missing parameter → 400 `urn:rozklad:problem:validation` with `errors[{field}]` (JSON path with list indexes, e.g. `grid[2].pair`). Malformed JSON → the same type with `errors: []`. Other framework errors get a stable `type` (`method-not-allowed`, `not-found`, …). | Forms could not highlight the field; responses had no `type`. |
| NUL in text (core) | `\u0000` in any JSON string → 400 `errors[{field}]`. | PostgreSQL rejects it; the client got a misleading 409 data-integrity. |
| Logs | pgjdbc `logServerErrorDetail=false`, data-integrity logs only SQLState and constraint. | Absence comments and catalog values were written to logs (ARCHITECTURE §10). |
| Gateway 429, core firewall | The rate limiter's 429 carries the gateway ProblemDetail (`too-many-requests`) and `Retry-After: 1`; a path the core firewall rejects (`/../`) is 400 `bad-request` ProblemDetail. The 414 of an over-long request line is still sent by Netty with an empty body. | Every other error path is RFC 9457. |

## Verified failures, round 2 (08.11.2026)

| Area | Decision | Why |
|---|---|---|
| Absent substitute | A substitute must not be absent at (date, pair) — now checked on create/edit (as before), on confirmation by the teacher (cabinet, bot) or the dispatcher, on a repeated confirmation request and on order publication: 409 `schedule-conflict`, `conflicts[{kind: UNAVAILABLE}]`. The change keeps its status; nothing is cancelled automatically. | An absence registered after the substitute had confirmed was published as №45 with an absent teacher. |
| `GET /api/admin/absences/{id}/affected` | Additive `asSubstitute` (boolean): `true` items are lessons of other teachers that the absent teacher was to take over by an open (DRAFT/PENDING/CONFIRMED) change; `decision` is that change. The SPA shows them as «Замінник відсутній» (needs action: cancel and decide again). | The affected list only showed the teacher's own lessons. |
| `GET /api/admin/dashboard` | New alert kind `SUBSTITUTE_ABSENT` (ERROR, link to the changes page) per open change whose substitute is absent. | The dashboard said only «1 підтверджену зміну ще не опубліковано». |
| `POST /api/admin/import/load` | An update keeps what the request does not carry: `teachers` absent (null) keeps the assignments, `controlForm`, `requiredRoomType`, `preferredRoomName`, `note` absent keep the stored values; an explicit `teachers: []` or a blank room name / note clears them. | A program or curriculum import erased the teacher of a timetable-derived item (importer.md §8). |
| Dates (core) | Every `LocalDate`/`YearMonth` of a query parameter or a JSON body must lie in the years 2000–2100, else 400 `validation` `errors[{field, "Дата має бути в межах 2000–2100 років."}]`. A database «data exception» (SQLState class 22) is 400 `bad-request`, no longer 409 «record already exists». | `?from=+999999999-12-28` gave 500 with an ERROR stack trace (anonymous), body dates gave a false 409. |
| Numbers (core) | A fractional number for an integer field (`"pair": 1.2`) is 400 «Очікується ціле число.» (Jackson `ACCEPT_FLOAT_AS_INT` off); an int overflow names its field («Число поза допустимим діапазоном.»). A JSON array body's field path carries the parameter name (`grid[0].kind`), like bean validation. | Values were truncated silently. |
| `POST /api/admin/import/timetable` | Honours `Idempotency-Key` (≤ 200 chars, stored with the draft, V1_402): a repeat with the same key and the same request returns the first draft (201, same `versionId`); the same key with another request → 409. The key lives as long as the draft. | A retry after a lost response created a duplicate draft version. |
| `GET/PUT /api/admin/settings` | Additive `version` (revision of the settings); PUT requires it (400 `errors[version]`), an older one → 409 `stale-data`. Saves are serialized, so of two parallel PUTs of one version exactly one wins. | The last of two parallel saves silently won. |
| Imported paper orders | `OrderDto`, `OrderDto.Summary` and `PublicChangesDto.order` get `imported` (V1_603; earlier imports are back-filled: published orders whose rows come from no change). Imported orders have no DOCX/PDF/PNG (no `order.published` is sent for them); the SPA does not offer those files and does not print the import time as the publication time. | The public page offered PDF/PNG links that answered 404. |
| `POST /api/admin/import/changes` | Additive `replace` (boolean) and result field `replaced`: with `replace: true` reviewed rows replace an earlier **imported** order with the same number, date and kind (rows, notes, labels, signature); an order the system published itself is never replaced (409 as before). The importer sends `replace: true` for reviewed drafts. | A misread order could not be corrected after apply. |
| Importer `ORDER_DOCUMENT` | A draft read by AI from a photo/scan carries a blocking report row until the admin reviews it: the job stays `NEEDS_REVIEW` (apply → 422 `IMPORT_NEEDS_REVIEW`). New `PUT /api/import/jobs/{id}/draft` (ADMIN) `{version, number, kind, parentNumber, date, weekdayLabel, parityLabel, rows:[{section?, groupCode, pairLabel, rowType?, fromDiscipline?, …, roomText?}], notes, signatoryPosition, signatoryName}` replaces the draft (missing `rowType` derived from the texts), marks it `reviewed`, re-matches it; stale `version` → 409 `stale-data`, another kind → 422 `DRAFT_NOT_EDITABLE`. `ImportJobDto` adds `version`. Row types follow the text: «Вичит.» without a replacement is FINISHED (also over a pair range), FINISHED only then; a contradicting AI answer is replaced with a WARN row. | The review step was not enforced and rows could not be edited; «Зв.зам.» rows were stored as FINISHED. |
| Substitution body ids | An unknown `teacherId` (absences), `groupId`, `newTeacherId`, `newRoomId`, `newDisciplineId`, `originalLessonId`, `absenceId` (changes) → 400 `validation` `errors[{field}]` (was 404 without a field). | The form could not tell which field was wrong. |
| Importer, notification, scheduler errors | Firewall rejections (`/../`, `;x=1`) → 400 `bad-request` ProblemDetail; method-validation fields without the Java method name (`size`, not `list.size`); importer: missing multipart part → `errors[{field: "file"}]`; notification render checks `format` before the body and names missing `id`/`date`/`kind`/`status`; scheduler: framework errors get a `type`, an unconvertible path parameter is `validation` with the field. | One error shape across services. |
