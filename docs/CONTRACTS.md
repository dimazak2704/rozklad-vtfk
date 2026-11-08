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

## 1. Core — public (no auth)

| Method & path | Response |
|---|---|
| `GET /api/public/meta` | `{collegeName, semester:{id,name,startsOn,endsOn}, today, weekNumber, parity, studyDay:bool, currentPair:{number,startsAt,endsAt,minutesLeft}?, nextPair?, bellProfile:{id,name}}` |
| `GET /api/public/search?q=&limit=10` | `[{type: GROUP|TEACHER|ROOM, id, label, sublabel, url}]` (fuzzy, Latin/Cyrillic tolerant) |
| `GET /api/public/groups` | `[{id, code, course, specialty:{letters,name}, department, shift}]` |
| `GET /api/public/teachers` | `[{id, shortName, displayName, cycleCommission}]` |
| `GET /api/public/rooms` | `[{id, name, type, capacity, building}]` |
| `GET /api/public/bells?date=` | `{profile:{id,name}, weekday, slots:[{pair, startsAt, endsAt}]}` (+ `profiles` list when no date) |
| `GET /api/public/schedule/{group\|teacher\|room}/{id}?from=&to=` | `ScheduleDto` below (max 31 days) |
| `GET /api/public/changes?date=` | `{date, parity, weekdayLabel, order:{id,number,publishedAt}?, groups:[{group:GroupRef, items:[ChangeView]}]}` |
| `GET /api/public/ics/{group\|teacher}/{id}.ics` | `text/calendar` for the semester (published lessons) |
| `GET /api/public/invites/{token}` | `{valid, teacherName, expiresAt}` |
| `POST /api/public/invites/{token}/accept` `{username, password}` | 201 `{username}`; 409 username taken; 410 expired |

```
ScheduleDto { owner:{type,id,label}, from, to, days:[DayDto] }
DayDto      { date, weekday (1..5), weekdayLabel, parity, studyDay, note?, lessons:[LessonView] }
LessonView  { id, pair, startsAt, endsAt, discipline:DisciplineRef, teacher:TeacherRef?, room:RoomRef?,
              group:GroupRef, subgroup (0..n), kind: REGULAR|SUBSTITUTED|CANCELLED|ADDITIONAL|MOVED|ROOM_CHANGED|BLOCK,
              online:bool, original:{discipline?,teacher?,room?,pair?}?, reasonText?, changeId? }
ChangeView  { pairLabel, rowType, from:{discipline?,teacher?}, reasonText?, to:{discipline?,teacher?}, room?, subgroupNote? }
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
| `POST /api/admin/timetable/versions/{id}/publish` `{effectiveFrom}` | 200 `{materializedLessons}` / 409 conflicts |
`TemplateLessonDto {id, weekday, pair, parity, group:GroupRef, subgroup, discipline, teacher?, room?, lessonKind, loadItemId?, pinned, online, streamId?}`

Generation:
| `POST /api/admin/generation/jobs` `{semesterId, scope:{groupIds?:[], courses?:[]}, baseVersionId?, timeLimitSeconds (10..600), weights?:{windows,brokenDays,latePairs,preferences,homeRoom}}` | 202 `{jobId}` |
| `GET /api/admin/generation/jobs?semesterId` / `GET /{jobId}` | `{id,status,startedAt,finishedAt,bestScore,progress:[{t,hard,soft}],resultVersionId?,analysis?,metricsBefore?,metricsAfter?,error?}` |
| `POST /api/admin/generation/jobs/{jobId}/cancel` | 202 |

Substitutions:
| `GET /api/admin/absences?from&to&teacherId` · `POST` `{teacherId,dateFrom,dateTo,pairs?,reasonCategory,comment?}` · `PUT/DELETE {id}` | |
| `GET /api/admin/absences/{id}/affected` | `[{lesson:LessonView, decision:{changeId,status,kind}?}]` |
| `GET /api/admin/lessons/{lessonId}/options` | `{lesson:LessonView, options:[Option]}` |
| `POST /api/admin/changes` `{kind, date, pairFrom, pairTo?, groupId, subgroup?, originalLessonId?, newDisciplineId?, newTeacherId?, newRoomId?, swapLessonId?, reasonCode, reasonText?, absenceId?}` | 201 `ChangeDto` (status after approval-mode rules) / 409 conflicts |
| `GET /api/admin/changes?date&status` · `PUT /{id}` · `DELETE /{id}` (→ CANCELLED) | |
| `POST /api/admin/changes/{id}/request-confirmation` · `POST /{id}/confirm` (admin override) | |
| `GET /api/admin/dashboard?date` | `{date, parity, absences:n, uncoveredLessons:n, pending:n, confirmed:n, declined:n, orderDraft:{id,itemCount}?, generation:{status}?, aiSpendToday?, alerts:[...]}` |
`Option {kind, score (0..100), title, reasons:[string], teacher?, discipline?, room?, swapLesson?, warnings:[string]}`
`ChangeDto {id, kind, status, date, pairFrom, pairTo, group, subgroup, original:{discipline,teacher,room}, new:{discipline?,teacher?,room?}, reasonCode, reasonText, absenceId?, confirmDeadline?, respondedAt?, orderId?, history:[{at,actor,from,to,comment}]}`

Orders:
| `GET /api/admin/orders?from&to&q&status` | page of `{id, number, date, kind, status, itemCount, publishedAt}` |
| `GET /api/admin/orders/draft?date=` | builds/refreshes the draft for the date → `OrderDto` |
| `GET /api/admin/orders/{id}` | `OrderDto {id, number?, date, kind, parentId?, status, title, weekdayLabel, parityLabel, items:[OrderItemDto], roomChanges:[OrderItemDto], notes:[string], signatoryPosition, signatoryName}` |
| `PUT /api/admin/orders/{id}/notes` `{notes:[string]}` | edit notes of a draft |
| `POST /api/admin/orders/{id}/publish` | 200 `OrderDto` (number assigned, changes APPLIED, event `order.published`) |
| `GET /api/admin/orders/numbering?semesterId` · `PUT {semesterId, firstNumber}` | `{semesterId, firstNumber, lastNumber?, nextNumber}`; next = max(last + 1, first); 422 `ORDER_NUMBER_USED` when `firstNumber ≤ last` |
`OrderItemDto {id, groupCode, pairLabel, rowType, fromDiscipline, fromTeacher, reasonText, toDiscipline, toTeacher, roomText, changeId?}`

Accounting: `GET /api/admin/accounting?semesterId&groupId` → `[{group, discipline, plan, fact, remaining, forecast, status, forecastFinishDate?, suggestion?}]`;
`GET /api/admin/accounting/teachers?month=2026-10` → `[{teacher, substitutionsCount, substitutionsHours, missedCount}]`.

Identity & settings: `POST /api/admin/invites {teacherId}` → `{token, url, expiresAt}`; `GET /api/admin/invites`;
`GET/PUT/DELETE /api/admin/teachers/{id}/account` (`PUT {username}` links an existing Keycloak account → `{teacherId,
teacherName, linked, userId, username, enabled}`; 422 `ACCOUNT_NOT_FOUND`/`ACCOUNT_DISABLED`, 409 taken, 503; `DELETE` → 204);
`GET/PUT /api/admin/settings` → `{approvalMode, confirmTimeoutMinutes, signatoryPosition, signatoryName, collegeName, hoursPerPair, showTeacherFullNames, activeBellProfileId}`.

Import apply (role ADMIN or SERVICE): `POST /api/admin/import/catalog` `{departments, specialties, teachers, disciplines, rooms, groups, aliases}`
(upsert by natural keys: group code, teacher shortName, discipline shortName, room name) → `{created, updated, ids:{...}}`;
`POST /api/admin/import/timetable` `{semesterId, name, lessons:[{weekday,pair,parity,groupCode,subgroup,disciplineShortName,teacherShortName?,roomName?,online}]}` → draft version id;
`POST /api/admin/import/load` `{semesterId, items:[...]}`; `POST /api/admin/import/changes` `{date, items:[...]}` (historic orders, optional).

SSE: `GET /api/events/stream` (ADMIN, TEACHER) → events `change.updated`, `absence.created`, `generation.progress`,
`generation.completed`, `order.published`, `schedule.changed`, `ping` (every 25 s). Data = JSON of the related DTO.

## 3. Core — teacher (role TEACHER)

`GET /api/me` → `{userId, username, roles, teacher:{id,shortName,displayName}?}`;
`GET /api/me/schedule?from&to` → `ScheduleDto`; `GET/POST /api/me/absences`, `DELETE /api/me/absences/{id}` (own only, future only);
`GET /api/me/changes?status` → changes where I am the new teacher; `POST /api/me/changes/{id}/confirm|decline` `{comment?}`;
`GET/PUT /api/me/preferences?semesterId` → `[{weekday,pair,kind,note}]`.

## 4. Core — internal (role SERVICE, client credentials)

`POST /api/internal/changes/{id}/reply` `{teacherId, decision: CONFIRM|DECLINE, comment?}` → `ChangeDto` (403 if teacher mismatch, 409 if not pending);
`POST /api/internal/absences` `{teacherId, dateFrom, dateTo, pairs?, reasonCategory, comment?, source:"BOT"}` → 201;
`GET /api/internal/catalog/snapshot` → `{groups:[{id,code,course,subgroups}], teachers:[{id,shortName,displayName,lastName,aliases}], disciplines:[{id,shortName,name,aliases}], rooms:[{id,name,type}], semesters:[...]}`;
`GET /api/internal/teachers/{id}` → teacher with keycloak link.

## 5. Importer (role ADMIN; `ai/parse-absence` also SERVICE)

| `POST /api/import/jobs` multipart `file`, `kind` (TIMETABLE_XLSX\|DISCIPLINE_PROGRAM\|CURRICULUM\|ORDER_DOCUMENT), `semesterId?`, `options?` (json) | 202 `ImportJobDto` |
| `GET /api/import/jobs?kind&status` · `GET /api/import/jobs/{id}` | `ImportJobDto {id, kind, fileName, status: UPLOADED|PARSING|NEEDS_REVIEW|READY|APPLYING|APPLIED|FAILED, createdAt, summary:{recognized, unknown, conflicts, warnings}, report:[ReportRow], draft:json, ai:{model, inputTokens, outputTokens, costUsd}?, error?}` |
| `PUT /api/import/jobs/{id}/mappings` `{mappings:[{type: TEACHER|DISCIPLINE|GROUP|ROOM, raw, targetId? , createNew?:{...}, addAlias:bool}]}` | re-normalizes, returns job |
| `POST /api/import/jobs/{id}/apply` | 202 → APPLIED with core result ids |
| `POST /api/import/ai/parse-absence` `{text, teacherId?, today}` | `{dateFrom, dateTo, pairs?, reasonCategory, confidence, clarification?}` |
| `GET /api/import/ai/usage` | `{today:{calls, inputTokens, outputTokens, costUsd}, dailyBudgetUsd, model:{text,vision}}` |
`ReportRow {severity: OK|INFO|WARN|ERROR, type, raw, matched?:{id,label,score}, suggestions:[{id,label,score}], location}`

## 6. Notification (role ADMIN unless stated)

| `GET /api/notify/bot` | `{username, link, mode: POLLING|WEBHOOK, connected}` |
| `GET /api/notify/bindings` · `DELETE /api/notify/bindings/{id}` | `[{id, type: GROUP_CHAT|TEACHER|ADMIN_CHAT|SUBSCRIBER, chatId(masked), title, groupId?, teacherId?, createdAt}]` |
| `POST /api/notify/bindings/codes` `{type, groupId?, teacherId?}` (ADMIN; TEACHER may create own TEACHER code) | `{code, deepLink, expiresAt}` |
| `GET /api/notify/bindings/me` (TEACHER, ADMIN with a linked teacher) | `{teacherId, teacherName, bound, binding?: BindingDto, botUsername, botLink}`; 422 `teacher-not-linked` |
| `GET /api/notify/deliveries?orderId&status` | delivery log |
| `POST /api/notify/test` | sends a test message to `TELEGRAM_OWNER_CHAT_ID` |
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
| `substitution.confirmation-requested` | core → notification | `changeId, teacherId, date, pair, startsAt, endsAt, groupCode, subgroup, disciplineName, originalTeacherName, roomName?, deadline` |
| `substitution.status-changed` | core → notification | `changeId, status, teacherId, date, groupCode` (edit bot message / notify admins) |
| `absence.reported` | core → notification | `absenceId, teacherId, teacherName, dateFrom, dateTo, pairs, source` |
| `order.published` | core → notification | `OrderPayload` (all fields of `OrderDto` + `semesterName`, `affectedGroups:[{groupId, code}]`, `affectedTeachers:[{teacherId, name}]`) |

`ProblemSnapshot { timeslots:[{weekday,pair}], shiftWindows:[{shift,weekday,firstPair,lastPair}], groups:[{id,code,size,shift,subgroups:[n]}],
rooms:[{id,name,type,capacity,maxParallel}], teachers:[{id,name,unavailable:[{weekday,pair}], unwanted:[{weekday,pair}]}],
units:[{unitId, loadItemId, groupId, subgroup, disciplineId, teacherId, weight: WHOLE|HALF, requiredRoomType?, preferredRoomId?, homeRoomId?,
pinned:{weekday,pair,parity,roomId}?}] }`
