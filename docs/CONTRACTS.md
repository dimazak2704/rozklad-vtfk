# Contracts — REST and events

All JSON uses camelCase, ISO dates (`2026-10-19`), times `HH:mm`, instants ISO-8601 UTC. IDs are UUID strings.
Errors are RFC 9457 ProblemDetail (`application/problem+json`): `{type,title,status,detail,errors:[{field,message}],conflicts:[Conflict]}`.
Paths below are as seen through the gateway (`http://localhost:8080`). Services expose the same paths.
Shared enums: `Parity = BOTH|NUMERATOR|DENOMINATOR`, `LessonKind = LECTURE|PRACTICE|LAB|SEMINAR|MIXED`,
`ChangeKind`, `ChangeStatus`, `RoomType` as in DOMAIN.md.

Common DTOs
```
Ref           {id, label}                                   // label = short display text
GroupRef      {id, code, course}
TeacherRef    {id, shortName, displayName}
DisciplineRef {id, shortName, name, color}
RoomRef       {id, name, type}
Conflict      {kind: TEACHER|GROUP|ROOM|CAPACITY|ROOM_TYPE|SHIFT|UNAVAILABLE, severity: HARD|SOFT,
               message, lessonIds:[uuid], date?, weekday?, pair}
Page<T>       {items:[T], page, size, total}
```

## 0. Authentication (sign-in service `auth`, contract version 2 of 12.11.2026)

Every protected call carries `Authorization: Bearer <access token>` issued by `auth`
(`docs/modules/auth.md`). What the services rely on:

| Claim | Value |
|---|---|
| `iss` | the one issuer of the environment: `http://localhost:8088/auth` (development), `https://<DOMAIN>/auth` (server) |
| `aud` | contains `rozklad-api` (every resource server checks it) |
| `sub` | the account id (UUID) for people; the client id for services |
| `roles` | flat list of `ADMIN`, `TEACHER`, `SERVICE` → authorities `ROLE_<NAME>`; a service token also has its own role: `SERVICE_NOTIFICATION` (`rozklad-notification`), `SERVICE_IMPORTER` (`rozklad-importer`) |
| `preferred_username` | the username (else the e-mail) for people; `service-account-<client_id>` for services; written to the actor columns |

Keys: `<issuer>/oauth2/jwks` (RS256, `kid` in the header). Service tokens: `client_credentials` at
`<auth>/auth/oauth2/token`. The SPA uses `rozklad-web` (code + PKCE, callback `/login/callback`, renewal
in a hidden iframe `/login/silent.html`). Version 2 dropped the old `realm_access.roles` claim and the
deprecated `keycloakUserId` of `GET /api/internal/teachers/{id}` (`docs/CHANGELOG-CONTRACTS.md`).

## 1. Core — public (no auth)

| Method & path | Response |
|---|---|
| `GET /api/public/meta` | `{collegeName, semester:{id,name,startsOn,endsOn}, today, weekNumber, parity, studyDay:bool, currentPair:{number,startsAt,endsAt,minutesLeft}?, nextPair?, bellProfile:{id,name}, nextStudyDay:{date,weekday,weekNumber,parity}?, now?, standTime?:true}` |
| `GET /api/public/search?q=&limit=10` | `[{type: GROUP|TEACHER|ROOM, id, label, sublabel, url}]` (fuzzy, Latin/Cyrillic tolerant) |
| `GET /api/public/groups` | `[{id, code, course, specialty:{letters,name}, department, shift}]` |
| `GET /api/public/teachers` | `[{id, shortName, displayName, cycleCommission, fullName, firstName}]`; `fullName` («Кренцін Михайло Дмитрович») and `firstName` are `null` unless the setting `showTeacherFullNames` is on and the first name is known |
| `GET /api/public/rooms` | `[{id, name, type, capacity, building, maxParallel}]` |
| `GET /api/public/bells?date=` | `{profile:{id,name}, weekday, slots:[{pair, startsAt, endsAt}]}` (+ `profiles` list when no date) |
| `GET /api/public/schedule/{group\|teacher\|room}/{id}?from=&to=` | `ScheduleDto` below (max 31 days) |
| `GET /api/public/changes?date=` | `{date, parity, studyDay, weekdayLabel, order:{id,number,publishedAt}?, amendments:[{id,number,publishedAt}], groups:[{group:GroupRef, items:[ChangeView]}]}` |
| `GET /api/public/ics/{group\|teacher}/{id}.ics` | `text/calendar` for the semester (published lessons) |
| `GET /api/public/invites/{token}` | `{valid, status, teacherName, expiresAt, kind: INVITE\|PASSWORD, username?}` (also password links, page `/password/:token`) |
| `POST /api/public/invites/{token}/accept` `{username, password}` | 201 `{username}`; 409 username taken; 410 expired/used/revoked or access revoked (`inviteStatus`) |

```
ScheduleDto { owner:{type,id,label}, from, to, days:[DayDto] }
DayDto      { date, weekday (1..5), weekdayLabel, parity, studyDay, note?, lessons:[LessonView], practices:[PracticeDto] }
PracticeDto { periodId, title, startsOn, endsOn, supervisors:[TeacherRef], groups:[GroupRef], courses:[int] }
            # group schedule: the practice the group is at; teacher schedule: practices the teacher supervises
            # (керівник практики); room schedule and days off: []
LessonView  { id, pair, startsAt, endsAt, discipline:DisciplineRef, teacher:TeacherRef?, room:RoomRef?,
              group:GroupRef, subgroup (0..n), kind: REGULAR|SUBSTITUTED|CANCELLED|ADDITIONAL|MOVED|ROOM_CHANGED|BLOCK,
              online:bool, original:{discipline?,teacher?,room?,pair?}?, reasonText?, changeId? }
ChangeView  { pairLabel, rowType, from:{discipline?,teacher?}, reasonText?, to:{discipline?,teacher?}, room?, subgroupNote?, orderNumber? }
```

## 2. Core — admin (role ADMIN)

Catalog (CRUD with `GET list (page, q, filters, sort)`, `GET {id}`, `POST`, `PUT {id}`, `DELETE {id}` → 204 or 409 if referenced):
`/api/admin/departments`, `/specialties`, `/cycle-commissions`, `/teachers` (`+ /{id}/aliases`, `/{id}/disciplines`),
`/disciplines` (`+ /{id}/aliases`), `/buildings`, `/rooms`, `/groups` (`+ /{id}/subgroups`).

Calendar: `/api/admin/semesters` (CRUD, `POST /{id}/activate`), `/api/admin/semesters/{id}/weeks` (GET weeks with parity,
`PUT /{weekStart}` override), `/api/admin/bell-profiles` (CRUD incl. slots), `/api/admin/calendar-periods` (CRUD, filter by semester),
`/api/admin/shift-windows` (GET/PUT).

Load: `/api/admin/load?semesterId&groupId` (CRUD) → `LoadItemDto {id, semesterId, group, discipline, lessonKind, hoursPerWeek,
plannedHours, splitSubgroups, teachers:[{subgroup, teacher}], requiredRoomType, preferredRoom, controlForm, source, pattern:{whole, half}}`;
`POST /api/admin/load/derive-from-version/{versionId}` → derived items (TIMETABLE_DERIVED).

Timetable:
| Method & path | Notes |
|---|---|
| `GET /api/admin/timetable/versions?semesterId` | list `{id,name,status,source,effectiveFrom,publishedAt,lessonCount,hardConflicts,softWarnings,metrics}` |
| `POST /api/admin/timetable/versions` `{semesterId,name,copyFromVersionId?}` | 201 draft |
| `GET /api/admin/timetable/versions/{id}/lessons?groupId&teacherId&roomId` | `[TemplateLessonDto]` |
| `POST/PUT/DELETE /api/admin/timetable/versions/{id}/lessons[/{lessonId}]` | edit draft; response includes `conflicts` for the touched slot |
| `POST /api/admin/timetable/versions/{id}/lessons/{lessonId}/move` `{weekday,pair,parity,roomId?}` | 200 or 409 `conflicts` (`?dryRun=true` for DnD preview) |
| `GET /api/admin/timetable/versions/{id}/conflicts` | `{hard:[Conflict], soft:[Conflict]}` |
| `GET /api/admin/timetable/versions/{id}/metrics` | `MetricsDto {groupWindows, teacherWindows, brokenDays, latePairs, maxPairsPerDay, perGroup:[...]}` |
| `GET /api/admin/timetable/versions/{a}/diff/{b}` | `{added:[..], removed:[..], moved:[{from,to}]}` |
| `POST /api/admin/timetable/versions/{id}/publish` `{effectiveFrom}` | 200 `{materializedLessons, keptLessons, coveredByChanges, conflicts, pendingDecisions}` / 409 conflicts |
`TemplateLessonDto {id, weekday, pair, parity, group:GroupRef, subgroup, discipline, teacher?, room?, lessonKind, loadItemId?, pinned, online, streamId?, version}`;
`PUT …/lessons/{lessonId}` and `POST …/move` accept `version` (the lesson's version the edit is based on): an older one → 409 `stale-data`.

Generation:
| `POST /api/admin/generation/jobs` `{semesterId, scope:{groupIds?:[], courses?:[]}, baseVersionId?, timeLimitSeconds (10..600), weights?:{windows,brokenDays,latePairs,preferences,homeRoom}}` | 202 `{jobId}` |
| `GET /api/admin/generation/jobs?semesterId` / `GET /{jobId}` | `{id,status,startedAt,finishedAt,bestScore,progress:[{t,hard,soft}],resultVersionId?,analysis?,metricsBefore?,metricsAfter?,error?}` |
| `POST /api/admin/generation/jobs/{jobId}/cancel` | 202 |

Substitutions:
| `GET /api/admin/absences?from&to&teacherId` · `GET {id}` (any status) · `POST` `{teacherId,dateFrom,dateTo,pairs?,reasonCategory,comment?}` · `PUT {id}` (+ required `version`) · `DELETE {id}` | |
| `GET /api/admin/absences/{id}/affected` | `[{date, lesson:LessonView, decision:{changeId,status,kind}?, asSubstitute}]` (`asSubstitute`: the absent teacher is the substitute of `decision`) |
| `GET /api/admin/lessons/{lessonId}/options` | `{lesson:LessonView, options:[Option], declined:[{teacher:TeacherRef, at}], past:bool}` — options in server order; may contain any subset of kinds (REPLACE_DISCIPLINE, MERGE_SUBGROUPS, SWAP, CANCEL; never REPLACE_TEACHER or ADDITIONAL) |
| `GET /api/admin/lessons/{lessonId}/teacher-availability?q=` | `[{teacher:TeacherRef, status: FREE\|BUSY\|ABSENT\|UNAVAILABLE\|DECLINED, detail?}]`, FREE first then alphabetical; `detail` «3 пара, 4КІ2» (BUSY) or «14:05» (DECLINED, Kyiv time); `q` filters by name |
| `GET /api/admin/absences/{id}/plan?date=` | `{date, lessons:[{lesson:LessonView, options:[Option], proposedIndex?, declined:[…], past}]}` — proposal per undecided lesson of the absent teacher on `date` (default the absence's first day; outside the absence → 400 `errors[date]`); nothing is created |
| `POST /api/admin/changes` `{kind, date, pairFrom, pairTo?, groupId, subgroup?, originalLessonId?, newDisciplineId?, newTeacherId?, newRoomId?, swapLessonId?, reasonCode, reasonText?, absenceId?}` | 201 `ChangeDto` (status after approval-mode rules) / 409 conflicts. Any pair 1–8 whatever the group's shift; REPLACE_DISCIPLINE without `newRoomId` gets the substitute's usual room when it is free (`new.room`), else none (the lesson's room) — DOMAIN §7. MERGE_SUBGROUPS: `originalLessonId` + `swapLessonId` (the other subgroup's lesson of the same discipline and pair), no `newTeacherId`/`newDisciplineId` |
| `POST /api/admin/changes/batch` `{changes:[CreateChangeRequest]}` (1..50) | 201 `[ChangeDto]` in request order; one transaction: any 400/409 creates nothing; field errors `changes[i].field` |
| `GET /api/admin/changes?date&status` · `PUT /{id}` · `DELETE /{id}` (→ CANCELLED) | |
| `POST /api/admin/changes/{id}/request-confirmation` · `POST /{id}/confirm` (admin override) | |
| `GET /api/admin/dashboard?date` | `{date, parity, absences:n, uncoveredLessons:n, pending:n, confirmed:n, declined:n, orderDraft:{id,itemCount}?, generation:{status}?, aiSpendToday?, alerts:[...]}` |
`Option {kind, score (position only, deprecated), title, reasons:[string], teacher?, discipline?, room?, swapLesson?, warnings:[string], date?, pair?, band: CLEAN|EASY_CANCEL|CAUTION|LAST, recommended:bool, suggestedRoom?}` (`swapLesson`: moved lesson of a SWAP or the other subgroup's lesson of a MERGE_SUBGROUPS; `date`/`pair` always null now)
`ChangeDto {id, kind, status, date, pairFrom, pairTo, group, subgroup, original:{discipline,teacher,room}, new:{discipline?,teacher?,room?}, reasonCode, reasonText, absenceId?, confirmDeadline?, respondedAt?, orderId?, history:[{at,actor,from,to,comment}], approvalMode: AUTO|CONFIRM|NOTIFY, …, teacherAnswer: CONFIRM|DECLINE|null}` (round 7: `teacherAnswer` is the assigned teacher's answer in the current round; NOTIFY + APPLIED + DECLINE = objection, an amendment is needed)

Orders:
| `GET /api/admin/orders?from&to&q&status` | page of `{id, number, date, kind, status, itemCount, publishedAt}` |
| `GET /api/admin/orders/draft?date=` | builds/refreshes the draft for the date → `OrderDto` |
| `DELETE /api/admin/orders/{id}` | discards a draft → 204 (the changes stay confirmed); deletes an **imported** paper order (`imported: true`) → 204, 409 while it has amendments; an order the system published → 422 `ORDER_ALREADY_PUBLISHED` |
| `GET /api/admin/orders/{id}` | `OrderDto {id, number?, date, kind, parentId?, status, title, weekdayLabel, parityLabel, items:[OrderItemDto], roomChanges:[OrderItemDto], notes:[string], signatoryPosition, signatoryName}` |
| `PUT /api/admin/orders/{id}/notes` `{notes:[string], version}` | edit notes of a draft (stale `version` → 409) |
| `POST /api/admin/orders/{id}/publish` | 200 `OrderDto` (number assigned, changes APPLIED, event `order.published`) |
| `GET /api/admin/orders/numbering?semesterId` · `PUT {semesterId, firstNumber}` | `{semesterId, firstNumber, lastNumber?, nextNumber}`; next = max(last + 1, first); 422 `ORDER_NUMBER_USED` when `firstNumber ≤ last` |
`OrderItemDto {id, groupCode, pairLabel, rowType, fromDiscipline, fromTeacher, reasonText, toDiscipline, toTeacher, roomText, changeId?}`

Accounting: `GET /api/admin/accounting?semesterId&groupId` → `[{group, discipline, plan, fact, remaining, forecast, status, forecastFinishDate?, suggestion?}]`;
`GET /api/admin/accounting/teachers?month=2026-10` → `[{teacher, substitutionsCount, substitutionsHours, missedCount}]`.

Identity & settings: `POST /api/admin/invites {teacherId}` → `{token, url, expiresAt}`; `GET /api/admin/invites`;
Access («Доступи», CHANGELOG-CONTRACTS «step 4»): `GET /api/admin/access?q&role&status` → `[AccessDto]`, `POST {email,
displayName?, roles, teacherId?}` → 201, `PATCH /{accountId}`, `POST /{accountId}/revoke|restore|password-link`,
`DELETE /{accountId}/google`;
`GET/PUT/DELETE /api/admin/teachers/{id}/account` (`PUT {username}` links an existing account of the sign-in service → `{teacherId,
teacherName, linked, userId, username, enabled}`; 422 `ACCOUNT_NOT_FOUND`/`ACCOUNT_DISABLED`, 409 taken, 503; `DELETE` → 204);
`GET/PUT /api/admin/settings` → `{approvalMode: NOTIFY (default)|CONFIRM|AUTO, confirmTimeoutMinutes, signatoryPosition, signatoryName, collegeName, hoursPerPair, showTeacherFullNames, activeBellProfileId, version}` (PUT needs the `version` of GET, stale → 409).

Import apply (role ADMIN or SERVICE_IMPORTER): `POST /api/admin/import/catalog` `{departments, specialties, teachers, disciplines, rooms, groups, aliases}`
(upsert by natural keys: group code, teacher shortName, discipline shortName, room name) → `{created, updated, ids:{...}}`;
`POST /api/admin/import/timetable` `{semesterId, name, lessons:[{weekday,pair,parity,groupCode,subgroup,disciplineShortName,teacherShortName?,roomName?,online}]}` → draft version id;
`POST /api/admin/import/load` `{semesterId, items:[...]}` (absent fields keep stored values; absent `splitSubgroups` updates the one item of group × discipline × lesson kind whatever its split, 400 `items[i].splitSubgroups` when both a whole-group and a split item exist); `POST /api/admin/import/changes` `{date, items:[...], replace?}` (historic orders, stored as `imported`; `replace` corrects an earlier import).
`POST /api/admin/import/timetable` honours `Idempotency-Key`. Dates in requests: years 2000–2100.

SSE: `GET /api/events/stream` (ADMIN, TEACHER) → events `change.updated`, `absence.created`, `generation.progress`,
`generation.completed`, `order.published`, `schedule.changed`, `ping` (every 25 s). Data = JSON of the related DTO.
The server ends a stream when the access token it was opened with expires (at most 30 min); the client
reconnects with a fresh token. 429 (`too-many-requests`) above 10 streams of one account, 503
(`service-unavailable`) above 1000 in all, both with `Retry-After: 30`.

## 3. Core — teacher (role TEACHER)

`GET /api/me` → `{userId, username, roles, teacher:{id,shortName,displayName}?}`;
`GET /api/me/schedule?from&to` → `ScheduleDto`; `GET/POST /api/me/absences`, `DELETE /api/me/absences/{id}` (own only, future only;
a report starts today to 90 days ahead and at most 10 reports per teacher in 24 hours: 422 `ABSENCE_TOO_FAR`, `ABSENCE_LIMIT`);
`GET /api/me/changes?status` → changes where I am the new teacher; `POST /api/me/changes/{id}/confirm|decline` `{comment?}`;
`GET/PUT /api/me/preferences?semesterId` → `[{weekday,pair,kind,note}]`.

## 4. Core — internal (service roles, client credentials)

Per service account: `changes/{id}/reply`, `absences` and `teachers/{id}` → SERVICE_NOTIFICATION (the bot);
`catalog/snapshot` → SERVICE_IMPORTER; any other `/api/internal/**` → 403. The plain SERVICE role opens none of them.


`POST /api/internal/changes/{id}/reply` `{teacherId, decision: CONFIRM|DECLINE, comment?}` → `ChangeDto` (403 if teacher mismatch; CONFIRM requests: 409 if not pending; NOTIFY changes (DOMAIN §7): CONFIRM keeps the status, DECLINE declines a CONFIRMED change or records an objection to an APPLIED one, the same answer again → 200 unchanged, another answer once declined/objected or a cancelled change → 409); the same rules for `POST /api/me/changes/{id}/confirm|decline`;
`POST /api/internal/absences` `{teacherId, dateFrom, dateTo, pairs?, reasonCategory, comment?, source:"BOT"}` → 201;
`GET /api/internal/catalog/snapshot` → `{groups:[{id,code,course,subgroups}], teachers:[{id,shortName,displayName,lastName,aliases}], disciplines:[{id,shortName,name,aliases}], rooms:[{id,name,type}], semesters:[...]}`;
`GET /api/internal/teachers/{id}` → `{id, lastName, firstName, middleName, shortName, displayName, cycleCommission, active, accountId, linked}` (`accountId` = `sub` of the account's tokens).

## 5. Importer (role ADMIN; `ai/parse-absence` also SERVICE)

| `POST /api/import/jobs` multipart `file`, `kind` (TIMETABLE_XLSX\|DISCIPLINE_PROGRAM\|CURRICULUM\|ORDER_DOCUMENT), `semesterId?`, `options?` (json) | 202 `ImportJobDto` |
| `GET /api/import/jobs?kind&status` · `GET /api/import/jobs/{id}` | `ImportJobDto {id, kind, fileName, status: UPLOADED|PARSING|NEEDS_REVIEW|READY|APPLYING|APPLIED|FAILED, createdAt, summary:{recognized, unknown, conflicts, warnings}, report:[ReportRow], draft:json, ai:{model, inputTokens, outputTokens, costUsd}?, error?}` |
| `PUT /api/import/jobs/{id}/mappings` `{mappings:[{type: TEACHER|DISCIPLINE|GROUP|ROOM, raw, targetId? , createNew?:{...}, addAlias:bool}]}` | re-normalizes, returns job |
| `PUT /api/import/jobs/{id}/draft` (ORDER_DOCUMENT) `{version, number, kind, date, …, rows:[…], notes, …}` | reviewed rows replace the draft; AI-read orders cannot be applied before this |
| `PUT /api/import/jobs/{id}/options` `{version, options:{groupCodes?, hoursPerWeek?, versionName?, …}}` | new application parameters (same validation as upload; `sheet`/`semesterNumber` cannot change), report rebuilt, returns job |
| `POST /api/import/jobs/{id}/apply` | 202 → APPLIED with core result ids |
| `POST /api/import/ai/parse-absence` `{text, teacherId?, today}` | `{dateFrom, dateTo, pairs?, reasonCategory, confidence, clarification?}` |
| `GET /api/import/ai/usage` | `{today:{calls, inputTokens, outputTokens, costUsd}, dailyBudgetUsd, model:{text,vision}}` |
`ReportRow {severity: OK|INFO|WARN|ERROR, type, raw, matched?:{id,label,score}, suggestions:[{id,label,score}], location}`

## 6. Notification (role ADMIN unless stated)

| `GET /api/notify/bot` | `{username, link, mode: POLLING|WEBHOOK, connected}` |
| `GET /api/notify/bindings` · `DELETE /api/notify/bindings/{id}` | `[{id, type: GROUP_CHAT|TEACHER|ADMIN_CHAT|SUBSCRIBER, chatId(masked), title, groupId?, teacherId?, createdAt}]` (+ `groupCode`, `digestEnabled`) |
| `PATCH /api/notify/bindings/{id}` `{digestEnabled}` (round 7) | 200 `BindingDto`; morning schedule at 07:30 for GROUP_CHAT/SUBSCRIBER only, else 422 `digest-not-supported`; 404 unknown; 400 `errors[digestEnabled]` |
| `POST /api/notify/bindings/codes` `{type, groupId?, teacherId?}` (ADMIN; TEACHER may create own TEACHER code) | `{code, deepLink, expiresAt}` |
| `GET /api/notify/bindings/me` (TEACHER, ADMIN with a linked teacher) | `{teacherId, teacherName, bound, binding?: BindingDto, botUsername, botLink}`; 422 `teacher-not-linked` |
| `GET /api/notify/deliveries?orderId&status` | delivery log |
| `POST /api/notify/test` | sends a test message to `TELEGRAM_OWNER_CHAT_ID` |
| `GET /api/notify/events/dead-letters` (ADMIN) | `{queue, count}`: events in `notification.q.events.dlq` (failed every retry); 503 when RabbitMQ cannot be asked |
| `POST /api/notify/events/dead-letters/replay` (ADMIN) | `{replayed}`: moves the messages now in the DLQ back to `notification.q.events`, each once; 503 when RabbitMQ cannot be asked |
| `GET /api/documents/orders/{orderId}.{docx\|pdf\|png}` (public for PUBLISHED orders) | file |
| `POST /api/documents/orders/render?format=docx\|pdf\|png` body `OrderPayload` (ADMIN, drafts) | file |

## 7. Events (exchange `rozklad.events`, topic; JSON; every payload has `eventId`, `occurredAt`, `version`=1)

Records live in `libs/contracts` (`ua.vtc.rozklad.contracts.events`).

| Routing key | Producer → consumers | Payload (besides envelope) |
|---|---|---|
| `timetable.version.published` | core → notification | `versionId, semesterId, effectiveFrom, name` |
| `schedule.changed` | core → notification | `dates:[date], groupIds:[uuid], teacherIds:[uuid], reason` (cache invalidation) |
| `generation.requested` | core → scheduler | `jobId, semesterId, timeLimitSeconds, weights, problem: ProblemSnapshot` |
| `generation.cancel` | core → scheduler | `jobId` |
| `generation.progress` | scheduler → core | `jobId, elapsedMs, hardScore, softScore` |
| `generation.completed` | scheduler → core | `jobId, status SOLVED|FAILED|CANCELLED, hardScore, softScore, assignments:[{unitId, weekday, pair, parity, roomId}], analysis:[{constraint, matchCount, score}], error?` |
| `substitution.confirmation-requested` | core → notification | `changeId, teacherId, date, pair, startsAt, endsAt, groupCode, subgroup, disciplineName (what the teacher teaches: the new discipline of REPLACE_DISCIPLINE), originalTeacherName, roomName?, deadline?` (MERGE_SUBGROUPS: `subgroup` 0 — the teacher takes the whole group) + additive (round 7, nullable) `mode: CONFIRM|NOTIFY` (null = CONFIRM; NOTIFY has no deadline), `kind`, `originalDisciplineName`, `reasonCode`; sent again with the same `changeId` after a reminder or a room edit of a pending request or a NOTIFY notice in force |
| `substitution.details-changed` | core → notification | `changeId, teacherId, date, pair, startsAt?, endsAt?, groupCode, subgroup, disciplineName, originalTeacherName?, roomName?, kind, originalDisciplineName?, reasonCode?` (the fields mean the same as in `confirmation-requested`; no `mode`, no `deadline`) — information only: what the teacher reads about a CONFIRM request already CONFIRMED changed (the dispatcher gave it a new room); nothing to answer, the change stays CONFIRMED with its answer |
| `substitution.teacher-replied` | core → notification | `changeId, teacherId, decision: CONFIRM|DECLINE, status, date, pair, groupCode` — a NOTIFY answer that kept the status: «Добре», or «Не можу» on an APPLIED change (objection, admin chats are told an amendment is needed) |
| `substitution.status-changed` | core → notification | `changeId, status, teacherId, date, groupCode` (edit bot message / notify admins) |
| `absence.reported` | core → notification | `absenceId, teacherId, teacherName, dateFrom, dateTo, pairs, source` |
| `order.published` | core → notification | `OrderPayload` (all fields of `OrderDto` + `semesterName`, `affectedGroups:[{groupId, code}]`, `affectedTeachers:[{teacherId, name}]`) |

Message properties (additive, optional): `message_id` = the event's `eventId`; header `X-Request-Id` = the
request id of the HTTP request (or of the consumed message) that caused the event. A consumer puts both into
its MDC (`requestId`, `eventId`) while it handles the message and forwards `X-Request-Id` on the calls it
makes; a message without the header gets a new request id. Events re-sent after a restart or a broker failure
(outbox resubmission, scheduler's resend of `generation.completed`) have no header.

`ProblemSnapshot { timeslots:[{weekday,pair}], shiftWindows:[{shift,weekday,firstPair,lastPair}], groups:[{id,code,size,shift,subgroups:[n]}],
rooms:[{id,name,type,capacity,maxParallel}], teachers:[{id,name,unavailable:[{weekday,pair}], unwanted:[{weekday,pair}]}],
units:[{unitId, loadItemId, groupId, subgroup, disciplineId, teacherId, weight: WHOLE|HALF, requiredRoomType?, preferredRoomId?, homeRoomId?,
pinned:{weekday,pair,parity,roomId}?, initial:{weekday,pair,parity,roomId}?, teacherRoomId?, online?}] }`
(`teacherRoomId`, round 7: the teacher's usual room for the discipline, DOMAIN §5; core sends `homeRoomId` as null since round 7;
`online` (additive, absent = false): a pinned online lesson whose room is kept for reference and occupied by nothing, DOMAIN §5)

Calendar periods (`/api/admin/calendar-periods`): request adds `supervisorIds?:[uuid]` (керівники практики:
only for `kind=PRACTICE`, active teachers, each once; field errors `supervisorIds` / `supervisorIds[i]`);
`CalendarPeriodDto` adds `supervisorIds:[uuid]` and `supervisors:[TeacherRef]` (empty for other kinds).
