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

### 0.1 Own account (`auth`, not the gateway; 17.11.2026)

The SPA calls `auth` directly at `<issuer>/api/account/…` (on the server the same origin under `/auth`) with its
access token; only people tokens (`sub` = account id, `roles` ∋ `ADMIN` or `TEACHER`) pass, service tokens → 403,
no token → 401. No CSRF (bearer only). The sign-in cookie that comes along (`credentials: 'include'`) only names the
session to keep. CORS only for `AUTH_CORS_ORIGINS` (development), with credentials.

| Endpoint | Answer |
|---|---|
| `GET /auth/api/account/password` | `{status: CHANGEABLE\|NO_PASSWORD\|DEMO, minLength, maxLength, reauthRequired}`: `NO_PASSWORD` = Google sign-in only (the SPA offers «Встановити пароль»), `DEMO` = a shared demo account of the server (kept); `reauthRequired` (added 17.11.2026; `false` unless `NO_PASSWORD`) = this browser's sign-in session does not prove a fresh Google sign-in of this account, so a first password needs the Google re-authentication below; 403 `account-inactive` |
| `POST /auth/api/account/password` `{currentPassword, newPassword}` | 204: the new password is stored and every **other** sign-in session of the account ends (this browser's stays; access tokens live until `exp`). 400 `validation` (a field missing or over 1024 characters), 400 `password-rejected` + `reason` (`too-short`, `too-long`, `same-as-username`, `same-as-email`, `too-common`, `same-as-current`), 403 `account-inactive` \| `demo-account`, 409 `password-not-set` (use the next endpoint), 422 `current-password-wrong`, 429 `too-many-attempts` |
| `POST /auth/api/account/initial-password` `{newPassword}` (17.11.2026) | The first password of a Google-only account. Besides the token, the request must carry this browser's sign-in cookie (`credentials: 'include'`), whose session is still signed in as the token's account and finished a Google sign-in of that account at most 10 minutes ago (server-side session attributes written by the Google success handler; nothing from the client counts). 204: the password is stored (the account then signs in with Google **and** the password, login = username or e-mail), the proof is used up, every **other** sign-in session ends. 400 `validation` (missing or over 1024 characters), 400 `password-rejected` + `reason`, 403 `reauth-required` (no cookie, a session of another account, no or a stale Google sign-in), 403 `account-inactive` \| `demo-account`, 409 `password-already-set` (use the change endpoint), 429 `too-many-attempts` |

Problems: `type` `urn:rozklad:auth:<code>`, the same `code` as a property, Ukrainian `detail`. A current-password
check counts in the sign-in lockout of the account's identifier and network (`login_attempt.method = PASSWORD_CHANGE`);
so does a first-password attempt (`PASSWORD_SET`; one without a fresh Google sign-in is a failure).

**Google re-authentication** (browser navigation, not an API call): `GET /auth/oauth2/authorization/google?reauth_return=<url>`
with `<url>` an absolute address on one of the SPA origins (`AUTH_WEB_ORIGINS`; anything else is ignored and the
sign-in is a plain one). It sends Google `prompt=select_account` and `max_age=0`; after a successful Google sign-in
(the allowlist as always) the browser goes back to `<url>`, after a failed one too. The SPA passes its current page
with `?password=set` and reopens the password dialog there, after a silent token renew: the callback gives the sign-in
session a new id, and an ID token issued before it has a stale `sid` that RP-initiated logout refuses.

## 1. Core — public (no auth)

| Method & path | Response |
|---|---|
| `GET /api/public/meta` | `{collegeName, semester:{id,name,startsOn,endsOn}, today, weekNumber, parity, studyDay:bool, currentPair:{number,startsAt,endsAt,minutesLeft}?, nextPair?, bellProfile:{id,name}, nextStudyDay:{date,weekday,weekNumber,parity}?, publicHistoryFrom, now?, standTime?:true}`; `publicHistoryFrom` = today − `changesHistoryDays`, the same for every caller |
| `GET /api/public/search?q=&limit=10` | `[{type: GROUP|TEACHER|ROOM, id, label, sublabel, url}]` (fuzzy, Latin/Cyrillic tolerant) |
| `GET /api/public/groups` | `[{id, code, course, specialty:{letters,name}, department, shift}]` |
| `GET /api/public/teachers` | `[{id, shortName, displayName, cycleCommission, fullName, firstName}]`; `fullName` («Кренцін Михайло Дмитрович») and `firstName` are `null` unless the setting `showTeacherFullNames` is on and the first name is known |
| `GET /api/public/rooms` | `[{id, name, type, capacity, building, maxParallel}]` |
| `GET /api/public/bells?date=` | `{profile:{id,name}, weekday, slots:[{pair, startsAt, endsAt}]}` (+ `profiles` list when no date) |
| `GET /api/public/schedule/{group\|teacher\|room}/{id}?from=&to=` | `ScheduleDto` below (max 31 days); visitors: `from` ≥ the Monday of the week of `publicHistoryFrom`, else 404 `history-not-public` |
| `GET /api/public/changes?date=` | `{date, parity, studyDay, weekdayLabel, order:{id,number,publishedAt}?, amendments:[{id,number,publishedAt}], groups:[{group:GroupRef, items:[ChangeView]}]}`; visitors: `date` ≥ `publicHistoryFrom`, else 404 `history-not-public` |
| `GET /api/public/ics/{group\|teacher}/{id}.ics` | `text/calendar` for the semester (published lessons); visitors get it from the Monday of the week of `publicHistoryFrom` |
| `GET /api/public/invites/{token}` | `{valid, status, teacherName, expiresAt, kind: INVITE\|PASSWORD, username?}` (also password links, page `/password/:token`) |
| `POST /api/public/invites/{token}/accept` `{username, password}` | 201 `{username}`; 409 username taken; 410 expired/used/revoked or access revoked (`inviteStatus`) |

**Public history window** (owner decision 14.11.2026). Visitors see substitutions and orders from
`publicHistoryFrom` (meta) on, every future date included; schedules and ICS, which carry substitutions, by whole
weeks from the Monday of that date's week. A request with a valid token of ADMIN or TEACHER has no limit; so has
the bot's SERVICE_NOTIFICATION on changes, schedules and ICS (the bot limits its other chats itself). Order
documents are the exception: there only ADMIN and TEACHER are staff, and every service token is a visitor (the bot
renders its pictures internally and never fetches them over this endpoint). An invalid or expired token is 401 as on
every path. Refusal: 404 ProblemDetail `{type: "urn:rozklad:problem:history-not-public", title: "Архів недоступний",
detail, code: "HISTORY_NOT_PUBLIC", publicHistoryFrom}`. Changes, schedules, ICS and order documents answer with
`Vary: Authorization`; answers to staff are `private` (changes and schedules `no-cache, private`; a visitor's changes
answer sets no Cache-Control of its own, so Spring Security's `no-store` default applies). The Telegram bot applies the
window to its chats that are not staff's, and bound group chats are visitors: an `order.published` dated before
`publicHistoryFrom` (back-filled or amended later; the documents' day rule) goes to teacher and admin chats only
(core unreachable: as before).

```
ScheduleDto { owner:{type,id,label}, from, to, days:[DayDto] }
DayDto      { date, weekday (1..5), weekdayLabel, parity, studyDay, note?, lessons:[LessonView], practices:[PracticeDto] }
PracticeDto { periodId, title, startsOn, endsOn, supervisors:[TeacherRef], groups:[GroupRef], courses:[int],
              kind:{code, name, color?, effect}, room:RoomRef? }
            # every period of a SUSPEND kind of the day (practice, НС, сесія…; the name is kept): group schedule —
            # the blocks the group is at ([] when a DAY_OFF applies); teacher schedule — the blocks the teacher
            # supervises ([] on a college-wide DAY_OFF); room schedule: []. `kind` is always present (absent only
            # from an older core), `room` is null when none (display only)
LessonView  { id, pair, startsAt, endsAt, discipline:DisciplineRef, teacher:TeacherRef?, room:RoomRef?,
              group:GroupRef, subgroup (0..n), parity: BOTH|NUMERATOR|DENOMINATOR?,
              kind: REGULAR|SUBSTITUTED|CANCELLED|ADDITIONAL|MOVED|ROOM_CHANGED|BLOCK,
              online:bool, original:{discipline?,teacher?,room?,pair?}?, reasonText?, changeId? }
            # parity: of the template row a base-timetable lesson comes from (BOTH = every week; NUMERATOR /
            # DENOMINATOR = «лише по чисельниках / знаменниках»), also when it was cancelled; null for a lesson
            # a change, an additional pair or a block created
ChangeView  { pairLabel, rowType, from:{discipline?,teacher?}, reasonText?, to:{discipline?,teacher?}, room?, subgroupNote?, orderNumber? }
```

## 2. Core — admin (role ADMIN)

Catalog (CRUD with `GET list (page, q, filters, sort)`, `GET {id}`, `POST`, `PUT {id}`, `DELETE {id}` → 204 or 409 if referenced):
`/api/admin/departments`, `/specialties`, `/cycle-commissions`, `/teachers` (`+ /{id}/aliases`, `/{id}/disciplines`),
`/disciplines` (`+ /{id}/aliases`), `/buildings`, `/rooms`, `/groups` (`+ /{id}/subgroups`).

Calendar: `/api/admin/semesters` (CRUD, `POST /{id}/activate`), `/api/admin/semesters/{id}/weeks` (GET weeks with parity,
`PUT /{weekStart}` override), `/api/admin/bell-profiles` (CRUD incl. slots), `/api/admin/calendar-periods` (CRUD, filter by semester),
`/api/admin/shift-windows` (GET/PUT), `/api/admin/period-kinds` (CRUD, «Види періодів»), `GET /api/admin/group-semesters?semesterId=`.

Period kinds (owner's decision 15.11.2026, option Б). A kind is data: `PeriodKindDto {id, code, name, effect, color?, aliases:[string],
noteTemplate?, builtin, active, position, usage, version}`, `PeriodEffect = DAY_OFF|SUSPEND|ONLINE|INFO` (behaviour comes from the
effect only; priority on overlap DAY_OFF > SUSPEND > ONLINE > INFO; BLOCK is not accepted yet → 400 `effect`). 15 builtin kinds
(К С БЗ НП ВП ПП Т ЗУ НС І Д А ФН Р ВКР) keep their code and are switched off instead of deleted.
| Method & path | Notes |
|---|---|
| `GET /api/admin/period-kinds?active=` · `GET /{id}` | by `position`, then `code`; `usage` = periods of the kind |
| `POST /api/admin/period-kinds` `{code, name, effect, color?, aliases?, noteTemplate?, active?, position?}` | 201 + `Location`; code/alias equal (case ignored) to another kind's code or alias → 409 «Код «НС» уже має вид «…».» |
| `PUT /api/admin/period-kinds/{id}` (+ `version`) | 200; builtin code changed → 400; an effect change of a kind with periods re-checks them like a period edit (409 `conflicts[]` for live changes; 409 plain when switching to/from ONLINE or to a non-SUSPEND effect while a period has a room or supervisors) |
| `PUT /api/admin/period-kinds/{id}?dryRun=true` | 200 `PeriodKindImpactDto {kind, effectChanged, periods, dates, groups:[GroupRef], lessons:{added, removed, updated}, conflicts, blockers:[string], canApply}`; rolled back, no event |
| `DELETE /api/admin/period-kinds/{id}` | 204; 409 builtin or in use |
| `GET /api/admin/group-semesters?semesterId=` | `[{semesterId, group:GroupRef, theoryWeeks?, exams?, source, updatedAt}]` by group code; 400 without `semesterId` |

Load: `/api/admin/load?semesterId&groupId` (CRUD) → `LoadItemDto {id, semesterId, group, discipline, lessonKind, hoursPerWeek,
plannedHours, planSource, splitSubgroups, teachers:[{subgroup, teacher}], requiredRoomType, preferredRoom, controlForm, source, pattern:{whole, half}}`
(`planSource` AUTO: the system counts `plannedHours` over the group's theory days and recounts it; FIXED: a set number; a
request without `plannedHours` makes the plan AUTO);
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
| `GET /api/admin/timetable/versions/{id}/metrics` | `MetricsDto {groupWindows, teacherWindows, brokenDays, latePairs, maxPairsPerDay, perGroup:[{group:GroupRef, windows, brokenDays, latePairs, maxPairsPerDay, onePairDays?, loneSubgroupSlots?}], hardConflicts?, teacherShiftWaits?, studentOnePairDays?, loneSubgroupSlots?, teacherDays?, teacherOnePairDays?, teacherOverloadDays?}` (fields with `?` are additive, genfix: `null` in metrics stored before them; counts per kind of week; `latePairs` = group-days with a «4-та пара зміни», `LatePairs`; `teacherWindows` excludes waits between shifts) |
| `GET /api/admin/timetable/versions/{a}/diff/{b}` | `{added:[..], removed:[..], moved:[{from,to}]}` |
| `POST /api/admin/timetable/versions/{id}/publish` `{effectiveFrom}` | 200 `{materializedLessons, keptLessons, coveredByChanges, conflicts, pendingDecisions}` / 409 conflicts |
`TemplateLessonDto {id, weekday, pair, parity, group:GroupRef, subgroup, discipline, teacher?, room?, lessonKind, loadItemId?, pinned, online, streamId?, version}`;
`PUT …/lessons/{lessonId}` and `POST …/move` accept `version` (the lesson's version the edit is based on): an older one → 409 `stale-data`.

Generation:
| `POST /api/admin/generation/jobs` `{semesterId, scope:{groupIds?:[], courses?:[]}, baseVersionId?, timeLimitSeconds? (10..600; absent = auto mode: the scheduler stops when the timetable stops getting better, at the latest at its safety cap), weights?:{windows,brokenDays,latePairs,preferences,homeRoom, teacherWindows?,teacherShiftWait?,stability?,subgroupsTogether?,studentOnePairDay?,teacherDays?,teacherOverload?,disciplineTwice?,specialRooms?}}` (each weight 0..1000; the 9 last ones are optional, absent → default, `teacherWindows` → `windows`) | 202 `{jobId}`; 422 `GENERATION_LOAD_EXCEEDS_SLOTS` with `problems:[{kind: GROUP\|TEACHER\|ROOM_TYPE, id, label, requiredPairs, availablePairs, message}]` when the load does not fit the grid (nothing is stored) |
| `GET /api/admin/generation/jobs?semesterId` / `GET /{jobId}` | `{id,status,startedAt,finishedAt,bestScore,progress:[{t,hard,soft}],resultVersionId?,analysis?,metricsBefore?,metricsAfter?,error?}` + additive (genfix) `scope:{groupIds,courses}` (as requested), `groups:[GroupRef]` (resolved, by course and code), `weights` (all 14, normalised), `hardScore?`, `softScore?` (parsed from `bestScore`), `outcome?:{moved, roomChanged, total, perGroup:[{group:GroupRef, moved, total}], roomAssigned?, added?, kept?}` (SOLVED only: units with a base lesson, moved = other weekday, pair or week; additive `roomAssigned` = same slot, the base had no room and got one; `added` = placed free units without a base lesson; `kept` = pinned, stream and out-of-scope lessons the solver did not move; `null` for older jobs), `legacyWeights?` (repair: `true` when the job stored only the five weights of the first schema, so the later rules did not exist for that run; `null` without weights) + additive (auto mode) `timeLimitSeconds` `null` for a job without a limit, `stopRequested` (the dispatcher stopped the running job; it stays RUNNING until the scheduler returns its best timetable), `stopReason?: NO_IMPROVEMENT\|TIME_LIMIT\|SAFETY_CAP\|STOPPED` (why a job with a timetable stopped; `null` for older jobs and jobs without a timetable) + additive (base score) `baseHardScore?`, `baseSoftScore?` (the solver's score of the timetable the run started from, under the run's own rules and weights, before solving: «Було» of the hard violations; `null` for a run from scratch, older jobs and older schedulers) |
| `POST /api/admin/generation/jobs/{jobId}/cancel` | 202 with the job. «Зупинити»: a QUEUED job is CANCELLED at once; a RUNNING one gets `stopRequested: true` and ends SOLVED with `stopReason: STOPPED` and a draft of the best timetable so far (CANCELLED when the scheduler had no complete timetable yet); stopping a stopped or cancelled job again returns it; 409 for a finished job |

Substitutions:
| `GET /api/admin/absences?from&to&teacherId` · `GET {id}` (any status) · `POST` `{teacherId,dateFrom,dateTo,pairs?,reasonCategory,comment?}` · `PUT {id}` (+ required `version`) · `DELETE {id}` | |
| `GET /api/admin/absences/{id}/affected` | `[{date, lesson:LessonView, decision:{changeId,status,kind}?, asSubstitute}]` (`asSubstitute`: the absent teacher is the substitute of `decision`) |
| `GET /api/admin/lessons/{lessonId}/options` | `{lesson:LessonView, options:[Option], declined:[{teacher:TeacherRef, at}], past:bool}` — options in server order; may contain any subset of kinds (REPLACE_DISCIPLINE, MERGE_SUBGROUPS, SWAP, CANCEL; never REPLACE_TEACHER or ADDITIONAL) |
| `GET /api/admin/lessons/{lessonId}/teacher-availability?q=` | `[{teacher:TeacherRef, status: FREE\|BUSY\|ABSENT\|UNAVAILABLE\|DECLINED, detail?}]`, FREE first then alphabetical; `detail` «3 пара, 4КІ2» (BUSY) or «14:05» (DECLINED, Kyiv time); `q` filters by name |
| `GET /api/admin/absences/{id}/plan?date=` | `{date, lessons:[{lesson:LessonView, options:[Option], proposedIndex?, declined:[…], past}]}` — proposal per undecided lesson of the absent teacher on `date` (default the absence's first day; outside the absence → 400 `errors[date]`); nothing is created |
| `POST /api/admin/changes` `{kind, date, pairFrom, pairTo?, groupId, subgroup?, originalLessonId?, newDisciplineId?, newTeacherId?, newRoomId?, swapLessonId?, reasonCode, reasonText?, absenceId?}` | 201 `ChangeDto` (status after approval-mode rules) / 409 conflicts. Any pair 1–8 whatever the group's shift; REPLACE_DISCIPLINE without `newRoomId` gets the substitute's usual room when it is free (`new.room`), else none (the lesson's room) — DOMAIN §7. MERGE_SUBGROUPS: `originalLessonId` + `swapLessonId` (the other subgroup's lesson of the same discipline and pair), no `newTeacherId`/`newDisciplineId` |
| `POST /api/admin/changes/batch` `{changes:[CreateChangeRequest]}` (1..50) | 201 `[ChangeDto]` in request order; one transaction: any 400/409 creates nothing; field errors `changes[i].field` |
| `GET /api/admin/changes?date&status` · `PUT /{id}` · `DELETE /{id}` (→ CANCELLED) | |
| `POST /api/admin/changes/{id}/request-confirmation` · `POST /{id}/confirm` (admin override) | request-confirmation for a pair that is already over → 422 `CHANGE_LESSON_OVER` (DOMAIN §7) |
| `GET /api/admin/dashboard?date` | `{date, parity, absences:n, uncoveredLessons:n, undecidedLessons:n, pending:n, confirmed:n, declined:n, orderDraft:{id,itemCount}?, generation:{status}?, aiSpendToday?, alerts:[...]}` |
`Option {kind, score (position only, deprecated), title, reasons:[string], teacher?, discipline?, room?, swapLesson?, warnings:[string], date?, pair?, band: CLEAN|EASY_CANCEL|CAUTION|LAST, recommended:bool, suggestedRoom?}` (`swapLesson`: moved lesson of a SWAP or the other subgroup's lesson of a MERGE_SUBGROUPS; `date`/`pair` always null now)
`ChangeDto {id, kind, status, date, pairFrom, pairTo, group, subgroup, original:{discipline,teacher,room}, new:{discipline?,teacher?,room?}, reasonCode, reasonText, absenceId?, confirmDeadline?, respondedAt?, orderId?, history:[{at,actor,from,to,comment}], approvalMode: AUTO|CONFIRM|NOTIFY, …, teacherAnswer: CONFIRM|DECLINE|null}` (round 7: `teacherAnswer` is the assigned teacher's answer in the current round; NOTIFY + APPLIED + DECLINE = objection, an amendment is needed; `confirmDeadline` is never later than the start of the first pair, or the end of the last pair while it is going on; a CONFIRM change created for a pair already over comes back CONFIRMED with `approvalMode` AUTO)

Orders:
| `GET /api/admin/orders?from&to&q&status` | page of `{id, number, date, kind, status, itemCount, publishedAt}` |
| `GET /api/admin/orders/draft?date=` | builds/refreshes the draft for the date → `OrderDto` |
| `DELETE /api/admin/orders/{id}` | discards a draft → 204 (the changes stay confirmed); deletes an **imported** paper order (`imported: true`) → 204, 409 while it has amendments; an order the system published → 422 `ORDER_ALREADY_PUBLISHED` |
| `GET /api/admin/orders/{id}` | `OrderDto {id, number?, date, kind, parentId?, status, title, weekdayLabel, parityLabel, items:[OrderItemDto], roomChanges:[OrderItemDto], notes:[string], signatoryPosition, signatoryName}` |
| `PUT /api/admin/orders/{id}/notes` `{notes:[string], version}` | edit notes of a draft (stale `version` → 409) |
| `POST /api/admin/orders/{id}/publish` | 200 `OrderDto` (number assigned, changes APPLIED, event `order.published`) |
| `GET /api/admin/orders/numbering?semesterId` · `PUT {semesterId, firstNumber}` | `{semesterId, firstNumber, lastNumber?, nextNumber}`; next = max(last + 1, first); 422 `ORDER_NUMBER_USED` when `firstNumber ≤ last` |
`OrderItemDto {id, groupCode, pairLabel, rowType, fromDiscipline, fromTeacher, reasonText, toDiscipline, toTeacher, roomText, changeId?}`

Accounting: `GET /api/admin/accounting?semesterId&groupId` → `[{group, discipline, plan, fact, remaining, forecast, status, forecastFinishDate?, suggestion?, hoursPerWeek, planSource}]`
(`planSource` AUTO | FIXED | MIXED);
`GET /api/admin/accounting/theory?semesterId&groupId` → `[{group, semesterWeeks, theoryWeeks, numeratorWeeks, denominatorWeeks, studyDays, graphTheoryWeeks?, offPeriods:[{id, kind:{code, name, color, effect}, title, startsOn, endsOn}]}]`
(groups with load, by course and code; weeks off theory are not planned, DOMAIN §9);
`GET /api/admin/accounting/teachers?month=2026-10` → `[{teacher, substitutionsCount, substitutionsHours, missedCount}]`.

Identity & settings: `POST /api/admin/invites {teacherId}` → `{token, url, expiresAt}`; `GET /api/admin/invites`;
Access («Доступи», CHANGELOG-CONTRACTS «step 4»): `GET /api/admin/access?q&role&status` → `[AccessDto]`, `POST {email,
displayName?, roles, teacherId?}` → 201, `PATCH /{accountId}`, `POST /{accountId}/revoke|restore|password-link`,
`DELETE /{accountId}/google`;
`GET/PUT/DELETE /api/admin/teachers/{id}/account` (`PUT {username}` links an existing account of the sign-in service → `{teacherId,
teacherName, linked, userId, username, enabled}`; 422 `ACCOUNT_NOT_FOUND`/`ACCOUNT_DISABLED`, 409 taken, 503; `DELETE` → 204);
`GET/PUT /api/admin/settings` → `{approvalMode: NOTIFY (default)|CONFIRM|AUTO, confirmTimeoutMinutes, signatoryPosition, signatoryName, collegeName, hoursPerPair, showTeacherFullNames, changesHistoryDays, activeBellProfileId, teacherMaxPairsPerDay, fridayMaxPairs, version}` (PUT needs the `version` of GET, stale → 409; `changesHistoryDays` 0..365, default 7, days of substitution history visitors see; a PUT without it keeps the stored value; `teacherMaxPairsPerDay` 1..8, default 4, the generator's soft daily limit of a teacher, kept the same way; `fridayMaxPairs` 1..8, default 3, the most pairs a group has on Friday, a hard rule of the generator (owner, 15.11.2026), kept the same way).

Import apply (role ADMIN or SERVICE_IMPORTER): `POST /api/admin/import/catalog` `{departments, specialties, teachers, disciplines, rooms, groups, aliases}`
(upsert by natural keys: group code, teacher shortName, discipline shortName, room name) → `{created, updated, ids:{...}}`;
`POST /api/admin/import/timetable` `{semesterId, name, lessons:[{weekday,pair,parity,groupCode,subgroup,disciplineShortName,teacherShortName?,roomName?,online}]}` → draft version id;
`POST /api/admin/import/load` `{semesterId, items:[...]}` (absent fields keep stored values; absent `splitSubgroups` updates the one item of group × discipline × lesson kind whatever its split, 400 `items[i].splitSubgroups` when both a whole-group and a split item exist); `POST /api/admin/import/changes` `{date, items:[...], replace?}` (historic orders, stored as `imported`; `replace` corrects an earlier import);
`POST /api/admin/import/calendar-periods[?dryRun=true]` `{academicYear, periods:[{sourceKey, kindCode, title, startsOn, endsOn, scope, groupCodes?, courses?, roomName?, studyMode?, orderNote?}], groupSemesters:[{groupCode, semesterNumber, theoryWeeks?, exams?}]}`
(the academic calendar: items split over the semesters of the year — key `sourceKey` or `sourceKey|S<n>`; upsert by key of `IMPORT` rows only, `IMPORT` rows of the year the request lacks are deleted, `MANUAL` rows are reported, never touched: those with a key the request produces in `manual`, the others of the same effect that share a group and dates with an item in `manualOverlaps` (hand-made rows, and edited imported rows whose key the file no longer produces); one calendar re-check, live-change clash → 409 `conflicts[]`, in a dry run listed with `canApply: false` and rolled back)
→ `{dryRun, academicYear, canApply, created, updated, unchanged, deleted, skippedManual, periods:[{sourceKey, storedKey, action: CREATE|UPDATE|UNCHANGED|DELETE|SKIP_MANUAL, periodId?, semesterId, kindCode, startsOn, endsOn, groupCodes}], manual:[{storedKey, periodId, title, differs}], manualOverlaps:[{periodId, kindCode, title, startsOn, endsOn, groupCodes, sourceKey}], outOfSemester:[{sourceKey, kindCode, startsOn, endsOn, groupCodes, kept:[{storedKey, semesterId, startsOn, endsOn}]}], groupSemesters:{created, updated, unchanged, skipped:[{groupCode, semesterNumber, reason}]}, conflicts, lessons:{added, removed, updated}}`.
`POST /api/admin/import/timetable` honours `Idempotency-Key`. Dates in requests: years 2000–2100.

SSE: `GET /api/events/stream` (ADMIN, TEACHER) → events `change.updated`, `absence.created`, `generation.progress`,
`generation.completed`, `order.published`, `schedule.changed`, `ping` (every 25 s). Data = JSON of the related DTO.
The server ends a stream when the access token it was opened with expires (at most 30 min); the client
reconnects with a fresh token. 429 (`too-many-requests`) above 10 streams of one account, 503
(`service-unavailable`) above 1000 in all, both with `Retry-After: 30`.

## 3. Core — teacher (role TEACHER)

`GET /api/me` → `{userId, username, roles, teacher:{id,shortName,displayName}?}`;
`GET /api/me/schedule?from&to` → `ScheduleDto`; `GET/POST /api/me/absences`, `DELETE /api/me/absences/{id}` (own only, future only;
a report starts today to 90 days ahead and at most 10 reports per teacher in 24 hours: 422 `ABSENCE_TOO_FAR`, `ABSENCE_LIMIT`;
an inactive teacher reports nothing himself: 422 `TEACHER_INACTIVE`);
`GET /api/me/changes?status` → changes where I am the new teacher; `POST /api/me/changes/{id}/confirm|decline` `{comment?}`;
`GET/PUT /api/me/preferences?semesterId` → `[{weekday,pair,kind,note}]`. `GET /api/me/template?semesterId` → `[TemplateLessonDto]`: the teacher's own lessons of the semester's published version (empty without one), for «мої пари» in the wishes grid.

## 4. Core — internal (service roles, client credentials)

Per service account: `changes/{id}/reply`, `absences`, `teachers/{id}` and `mail-recipients` → SERVICE_NOTIFICATION (the bot);
`catalog/snapshot` → SERVICE_IMPORTER; any other `/api/internal/**` → 403. The plain SERVICE role opens none of them.


`POST /api/internal/changes/{id}/reply` `{teacherId, decision: CONFIRM|DECLINE, comment?}` → `ChangeDto` (403 if teacher mismatch; CONFIRM requests: 409 if not pending; NOTIFY changes (DOMAIN §7): CONFIRM keeps the status, DECLINE declines a CONFIRMED change or records an objection to an APPLIED one, the same answer again → 200 unchanged, another answer once declined/objected or a cancelled change → 409); the same rules for `POST /api/me/changes/{id}/confirm|decline`;
`POST /api/internal/absences` `{teacherId, dateFrom, dateTo, pairs?, reasonCategory, comment?, source:"BOT"}` → 201;
`GET /api/internal/catalog/snapshot` → `{groups:[{id,code,course,subgroups}], teachers:[{id,shortName,displayName,lastName,aliases}], disciplines:[{id,shortName,name,aliases}], rooms:[{id,name,type}], semesters:[...], periodKinds:[{id, code, name, effect, color, aliases, noteTemplate, active}]}` (`periodKinds`: every kind, inactive ones too);
`GET /api/internal/teachers/{id}` → `{id, lastName, firstName, middleName, shortName, displayName, cycleCommission, active, accountId, linked}` (`accountId` = `sub` of the account's tokens);
`GET /api/internal/mail-recipients?teacherId={uuid}` | `?accountId={uuid}` | `?role=ADMIN` (exactly one; other roles → 400) → `[{accountId, teacherId?, displayName, username?, email?, googleLinked, roles[], enabled, revoked, demo}]`: the people accounts of `auth` an e-mail would reach (`email` = the sign-in address, lower case, `null` for accounts created by invite; `googleLinked` = Google confirmed it). Facts only: disabled, revoked and demo accounts are listed, the mail channel decides. An unknown teacher or account, a teacher without an account and an account `auth` lost → `[]`; `auth` down → 503 `identity-unavailable`.

## 5. Importer (role ADMIN; `ai/parse-absence` also SERVICE)

| `POST /api/import/jobs` multipart `file`, `kind` (TIMETABLE_XLSX\|DISCIPLINE_PROGRAM\|CURRICULUM\|ORDER_DOCUMENT\|ACADEMIC_CALENDAR_XLSX), `semesterId?`, `options?` (json) | 202 `ImportJobDto` |
| `GET /api/import/jobs?kind&status` · `GET /api/import/jobs/{id}` | `ImportJobDto {id, kind, fileName, status: UPLOADED|PARSING|NEEDS_REVIEW|READY|APPLYING|APPLIED|FAILED, createdAt, summary:{recognized, unknown, conflicts, warnings}, report:[ReportRow], draft:json, ai:{model, inputTokens, outputTokens, costUsd}?, error?}` |
| `PUT /api/import/jobs/{id}/mappings` `{mappings:[{type: TEACHER|DISCIPLINE|GROUP|ROOM, raw, targetId? , createNew?:{...}, addAlias:bool}]}` | re-normalizes, returns job |
| `PUT /api/import/jobs/{id}/draft` (ORDER_DOCUMENT) `{version, number, kind, date, …, rows:[…], notes, …}` | reviewed rows replace the draft; AI-read orders cannot be applied before this |
| `PUT /api/import/jobs/{id}/options` `{version, options:{groupCodes?, hoursPerWeek?, versionName?, …}}` | new application parameters (same validation as upload; `sheet`/`semesterNumber` cannot change), report rebuilt, returns job |
| `POST /api/import/jobs/{id}/apply` | 202 → APPLIED with core result ids |
| `POST /api/import/ai/parse-absence` `{text, teacherId?, today}` | `{dateFrom, dateTo, pairs?, reasonCategory, confidence, clarification?}` |
| `GET /api/import/ai/usage` | `{today:{calls, inputTokens, outputTokens, costUsd}, dailyBudgetUsd, model:{text,vision}}` |
`ReportRow {severity: OK|INFO|WARN|ERROR, type, raw, matched?:{id,label,score}, suggestions:[{id,label,score}], location}`
(`type` adds `PERIOD_KIND`, `PERIOD` for ACADEMIC_CALENDAR_XLSX; a code of a kind with effect ONLINE is a blocking ERROR `PERIOD_KIND`
until skipped, because the file has no study mode.) ACADEMIC_CALENDAR_XLSX (графік освітнього процесу): XLSX only,
`semesterId` ignored; options `{sheet?, academicYear?, skipCodes?, skipGroups?, titles?, rooms?}` (defaults: НС «Основи національного
спротиву», room «401»); draft `{academicYear, sheet, groups, codes, legend, periods, groupSemesters, check}` (`check` = core's last dry
run); one apply step `calendar` → `POST /api/admin/import/calendar-periods` with `Idempotency-Key: import-job:<id>:calendar`
(re-sendable; 409 `conflicts[]` → back to NEEDS_REVIEW). Details: `docs/modules/importer.md`.

## 6. Notification (role ADMIN unless stated)

| `GET /api/notify/bot` | `{username, link, mode: POLLING|WEBHOOK, connected}` |
| `GET /api/notify/bindings` · `DELETE /api/notify/bindings/{id}` | `[{id, type: GROUP_CHAT|TEACHER|ADMIN_CHAT|SUBSCRIBER, chatId(masked), title, groupId?, teacherId?, createdAt}]` (+ `groupCode`, `digestEnabled`, `telegramAccount`) |
| `PATCH /api/notify/bindings/{id}` `{digestEnabled}` (round 7) | 200 `BindingDto`; morning schedule at 07:30 for GROUP_CHAT/SUBSCRIBER only, else 422 `digest-not-supported`; 404 unknown; 400 `errors[digestEnabled]` |
| `POST /api/notify/bindings/codes` `{type, groupId?, teacherId?}` (ADMIN; TEACHER may create own TEACHER code) | `{code, deepLink, expiresAt}`; TEACHER and ADMIN_CHAT codes expire in 15 min, others in 24 h |
| `GET /api/notify/bindings/me` (TEACHER, ADMIN with a linked teacher) | `{teacherId, teacherName, bound, binding?: BindingDto, botUsername, botLink}`; 422 `teacher-not-linked` |
| `DELETE /api/notify/bindings/me` (TEACHER, ADMIN with a linked teacher) | 204: the caller's own TEACHER chat is unlinked (also when none); 422 `teacher-not-linked` |
| `GET /api/notify/deliveries?orderId&status` | delivery log |
| `POST /api/notify/test` | sends a test message to `TELEGRAM_OWNER_CHAT_ID` |
| `GET /api/notify/email/me` (TEACHER, ADMIN; the account is the token's `sub`) | `{channel:{enabled, reason?}, address?, addressStatus: VERIFIED\|UNVERIFIED\|MISSING, verifiedBy?: GOOGLE\|TRUSTED_DOMAIN, enabled, version?, kinds:[{kind, audience: TEACHER\|ADMIN, label, description, enabled, defaultEnabled, switchable}]}`: own e-mail settings; teacher kinds for an account linked to a teacher, dispatcher kinds for ADMIN; a kind's `enabled` is the person's choice and is kept while the master `enabled` is off (a letter goes only when both are on); 503 core down |
| `PUT /api/notify/email/me` `{enabled, kinds?: {KIND: bool}, version?}` (TEACHER, ADMIN) | 200 the same; 400 `errors[enabled]`, `errors[kinds.X]` (unknown kind); 422 `email-kind-not-allowed`; 409 outdated `version` |
| `POST /api/notify/email/me/test` (TEACHER, ADMIN) | 202 `{status: "QUEUED"}`: a test letter to the caller's own verified address; 422 `email-account-inactive` (disabled, revoked or demo account) \| `email-missing` \| `email-not-verified`; 503 `email-disabled`; 429 `email-test-limit` (3 an hour) |
| `GET /api/notify/email/status` | `{enabled, mode: OFF\|DEV_CATCHER\|SMTP, host, port, from, replyTo, tls: STARTTLS\|SSL\|NONE, problem?}` (never the SMTP username or password) |
| `GET /api/notify/email/deliveries?status&kind&page&size` | letter log, newest first: page of `{id, kind, to (masked), accountName, subject, status: QUEUED\|RETRYING\|SENT\|FAILED\|SUPERSEDED, attempts, error, createdAt, sentAt, nextAttemptAt}` |
| `GET /api/notify/events/dead-letters` (ADMIN) | `{queue, count}`: events in `notification.q.events.dlq` (failed every retry); 503 when RabbitMQ cannot be asked |
| `POST /api/notify/events/dead-letters/replay` (ADMIN) | `{replayed}`: moves the messages now in the DLQ back to `notification.q.events`, each once; 503 when RabbitMQ cannot be asked |
| `GET /api/documents/orders/{orderId}.{docx\|pdf\|png}` (public for PUBLISHED orders; visitors only for orders dated from core's `publicHistoryFrom`, else 404 `history-not-public`; ADMIN or TEACHER token: any; `Vary: Authorization`, staff answers `private`; core unreachable for a past-dated order of a visitor → 503) | file |
| `POST /api/documents/orders/render?format=docx\|pdf\|png` body `OrderPayload` (ADMIN, drafts) | file |

## 7. Events (exchange `rozklad.events`, topic; JSON; every payload has `eventId`, `occurredAt`, `version`=1)

Records live in `libs/contracts` (`ua.vtc.rozklad.contracts.events`).

| Routing key | Producer → consumers | Payload (besides envelope) |
|---|---|---|
| `timetable.version.published` | core → notification | `versionId, semesterId, effectiveFrom, name` |
| `schedule.changed` | core → notification | `dates:[date], groupIds:[uuid], teacherIds:[uuid], reason` (cache invalidation) |
| `generation.requested` | core → scheduler | `jobId, semesterId, timeLimitSeconds?, weights, problem: ProblemSnapshot` (`timeLimitSeconds` positive = the old fixed limit; absent/null = auto mode, additive: deploy the scheduler before core) |
| `generation.cancel` | core → scheduler | `jobId` |
| `generation.progress` | scheduler → core | `jobId, elapsedMs, hardScore, softScore` |
| `generation.completed` | scheduler → core | `jobId, status SOLVED|FAILED|CANCELLED, hardScore, softScore, assignments:[{unitId, weekday, pair, parity, roomId}], analysis:[{constraint, matchCount, score, constraintId?, bonus?, scopeMatchCount?}], error?, stopReason?` (additive, genfix: `constraintId` ASCII id, `bonus` true for reward rows, absent = false; `scopeMatchCount` matches involving a free unit, absent = unknown → use `matchCount`; additive, auto mode: `stopReason` `NO_IMPROVEMENT\|TIME_LIMIT\|SAFETY_CAP\|STOPPED` for a job with a timetable, absent otherwise and from older schedulers; a CANCELLED completion with assignments is the best timetable of a stopped job, which core keeps as a draft; additive, base score: `baseScore?:{hardScore, softScore}` the solver's score of the starting timetable (base version, warm-started) under the run's own weights and settings before solving, units without a starting place count for nothing; absent for a run from scratch, without a timetable and from older schedulers) |
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

`ProblemSnapshot { timeslots:[{weekday,pair}], shiftWindows:[{shift,weekday,firstPair,lastPair}], groups:[{id,code,size,shift,subgroups:[n],fridayMaxPairs?}],
rooms:[{id,name,type,capacity,maxParallel}], teachers:[{id,name,unavailable:[{weekday,pair}], unwanted:[{weekday,pair}], maxPairsPerDay?}],
units:[{unitId, loadItemId, groupId, subgroup, disciplineId, teacherId, weight: WHOLE|HALF, requiredRoomType?, preferredRoomId?, homeRoomId?,
pinned:{weekday,pair,parity,roomId}?, initial:{weekday,pair,parity,roomId}?, teacherRoomId?, online?, siblingKey?}] }`
(`teacherRoomId`, round 7: the teacher's usual room for the discipline, DOMAIN §5; core sends `homeRoomId` as null since round 7;
`online` (additive, absent = false): a pinned online lesson whose room is kept for reference and occupied by nothing, DOMAIN §5;
genfix, additive: `maxPairsPerDay` the teacher's daily limit (absent = none; core sends the setting `teacherMaxPairsPerDay`);
owner's answers round 2, additive: `groups[].fridayMaxPairs` the most pairs of the group on Friday, hard for the group-days the run may change
(absent = no limit; core sends the setting `fridayMaxPairs` for every group);
`siblingKey` = `loadItemId:w1|w2|…|h`, equal on the subgroup copies of one weekly occurrence of a split item (free units of an
item with 2+ subgroups only); `initial` is also the base placement for stability, and the `teacherId` of a unit matched to a base
lesson is that lesson's teacher; `requiredRoomType` is only the load's own type: core derives none from the base version,
the scheduler reads the base rooms from `initial`/`pinned` for the soft rule `specialRooms`)

`GenerationWeights {windows, brokenDays, latePairs, preferences, homeRoom, teacherWindows, teacherShiftWait, stability,
subgroupsTogether, studentOnePairDay, teacherDays, teacherOverload, disciplineTwice, specialRooms}`: soft weights, 0 = off,
never negative. `windows` weighs nothing since group windows became a hard rule of the scheduler (owner's answers round 2,
15.11.2026); it stays for compatibility and as the fallback of an absent `teacherWindows`. The last nine are additive: an absent one takes its default, an absent `teacherWindows` equals `windows` (one
weight covered groups and teachers before). `DEFAULT` = (30, 10, 0, 5, 1, 10, 3, 1, 30, 10, 5, 3, 10, 30), tuned by replaying
the college's real generation requests (scheduler `SnapshotReplayTest`); since the owner's answers of 15.11.2026 `latePairs`
is 0 (the 4th pair is normal) and `subgroupsTogether` 30. The defaults and the statistics behind them are in CHANGELOG-CONTRACTS «Generator
quality» and «Owner's answers». `specialRooms`: a lesson of a load item that keeps to the sports hall or the sports ground (its
base lessons with a room all stood there or, without a base, its preferred room is one or its teacher's usual room is one
and its discipline is nowhere in an ordinary room) is placed in another kind of
room (per week occurrence). The subgroup copies of one occurrence (`siblingKey`) of two teachers in one slot is a hard rule
of the scheduler (no weight); `subgroupsTogether` weighs what it cannot demand: one teacher's two copies on neighbouring
pairs, a key whose copies cannot all be parallel by the data (one teacher for two of three copies, no common free slot),
and a lone subgroup lesson. `contracts.LatePairs` holds the one definition of a «4-та пара зміни»: a pair
whose position in the group's shift window is 3 or more (0-based): shift 1 the 4th pair, shift 2 the 8th, none on Friday shift 2.

Calendar periods (`/api/admin/calendar-periods`): request adds `supervisorIds?:[uuid]` (керівники: only for a kind with effect
SUSPEND, active teachers, each once; field errors `supervisorIds` / `supervisorIds[i]`), `kindId?:uuid` and `roomId?:uuid` (SUSPEND
only, display only); `kind` (legacy enum HOLIDAY|NO_CLASSES|PRACTICE|STUDY_MODE|EXAM_SESSION) is optional and deprecated, kept one
release: `kindId` wins, else `kind` → its builtin kind (К, БЗ, НП, ФН, І), else 400 `kindId` «Вкажіть вид періоду.»; an inactive kind
cannot be chosen for a new period or a kind change; `studyMode` iff effect ONLINE. Any PUT on an imported row makes it `MANUAL`.
`CalendarPeriodDto` adds `supervisorIds:[uuid]`, `supervisors:[TeacherRef]` (empty for other effects), `kindId`, `periodKind:{id, code,
name, color?, effect}`, `roomId?`, `room?:RoomRef`, `source: MANUAL|IMPORT`, `sourceKey?`; `kind` stays (deprecated): the builtin's
legacy value for К/БЗ/НП/ФН/І, else by effect DAY_OFF→NO_CLASSES, SUSPEND→PRACTICE, ONLINE→STUDY_MODE, INFO→EXAM_SESSION.
