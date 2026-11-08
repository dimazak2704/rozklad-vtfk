# Substitutions, orders and accounting (core)

Daily work of the dispatcher: a teacher is absent → the affected lessons get ranked options →
changes are confirmed by the substitute teachers → the confirmed changes of a date become the
order (розпорядження) → publication applies them to the dated lessons and sends the order to
Telegram → accounting compares the plan with the lessons. Rules: DOMAIN §7–§9; contracts:
CONTRACTS §1–§4, §7; deviations: `docs/CHANGELOG-CONTRACTS.md` («Substitutions, orders, accounting»).

## 1. Modules and dependencies

```
accounting  ← substitution ← order
    ▲              │           │
    └── TeacherActivityProvider (interface in accounting, implemented in substitution)
```

| Module | Owns | Public API (base package) |
|---|---|---|
| `accounting` | plan/fact/forecast, teacher statistics endpoint | `AccountingQueries`, `DisciplineProgress`, `AccountingStatus`, `TeacherActivityProvider`, `TeacherActivity` |
| `substitution` | absences, affected lessons, options, changes, confirmations, reminders, preferences | `ChangePublication` (apply changes on publication), `SubstitutionOverview` (dashboard figures), entities and repositories |
| `order` | drafts, rows, numbering, publication, amendments, public changes, dashboard | entities and repositories |

* substitution needs accounting (REPLACE_DISCIPLINE prefers disciplines behind plan, skips finished
  ones), so accounting must not depend on substitution: the teacher statistics read changes through
  `TeacherActivityProvider`, an interface that accounting declares and substitution implements.
* The dashboard lives in `order`: it is the only module of the daily workflow that may read both
  substitutions and orders without a cycle.
* Integration: the calendar is read only through `calendar.CalendarQueries`. `SubstitutionCalendar`,
  `OrderCalendar` and `AccountingCalendar` are thin adapters that add the modules' 422 messages
  (no repository access, no own parity or bell rules). Settings come from `settings.SettingsService`
  (the module readers `SubstitutionSettings`, `OrderSettings`, `AccountingSettings` were removed; the
  confirmation timeout default is the settings default, 120 min). `LessonView`s come from
  `timetable.ScheduleQueries` (the module's own mapping was removed), refs are `catalog.*Ref`
  (`shared.ref` and `shared.time.StudyWeeks` were removed), `ReasonCode` moved to `shared.domain`
  with the printing rule shared with timetable.
* Shared files: `shared.text.UkrainianText` (weekday/month names, plural forms, collator; also the
  schedule labels merged from timetable), `shared.sse.SseAfterCommit` (SSE after commit).

## 2. Endpoints

| Path | Who | What |
|---|---|---|
| `GET/POST /api/admin/absences`, `GET/PUT/DELETE /{id}`, `GET /{id}/affected` | ADMIN | absences; list = active absences overlapping `[from, to]` (default today + 31 days) |
| `GET /api/admin/lessons/{lessonId}/options` | ADMIN | ranked options of a SCHEDULED lesson (band, recommended, suggested room, decliners, past) |
| `GET /api/admin/lessons/{lessonId}/teacher-availability?q=` | ADMIN | every active teacher's state at the lesson's pair (manual «Інший викладач…») |
| `GET /api/admin/absences/{id}/plan?date=` | ADMIN | day plan: a proposal per undecided lesson, nothing created |
| `GET/POST /api/admin/changes`, `POST /batch`, `GET/PUT/DELETE /{id}`, `POST /{id}/request-confirmation`, `POST /{id}/confirm` | ADMIN | changes and their workflow; `batch` = several changes in one transaction |
| `GET /api/admin/orders`, `GET /draft?date=`, `GET /{id}`, `PUT /{id}/notes`, `POST /{id}/publish` | ADMIN | orders |
| `GET /api/admin/dashboard?date` | ADMIN | daily figures and alerts |
| `GET /api/admin/accounting?semesterId&groupId`, `GET /api/admin/accounting/teachers?month=2026-10` | ADMIN | accounting |
| `GET /api/public/changes?date` | anyone | published changes of a date |
| `GET /api/me/changes?status`, `POST /api/me/changes/{id}/confirm\|decline` | TEACHER/ADMIN with a linked teacher | «мої заміни» |
| `GET/POST /api/me/absences`, `DELETE /api/me/absences/{id}` | same | own absences (cancel only before they start) |
| `GET/PUT /api/me/preferences?semesterId` | same | unwanted/unavailable grid (PUT replaces it) |
| `POST /api/internal/changes/{id}/reply`, `POST /api/internal/absences` | SERVICE | Telegram bot |

The account of a teacher is found by `teacher.keycloak_user_id = sub`; an account without a
teacher gets 403 on `/api/me/*`.

## 3. Changes (DOMAIN §7)

* **Kinds and required fields** — `ChangeKindRules` (400 per field): REPLACE_TEACHER (lesson + new
  teacher, the discipline stays), REPLACE_DISCIPLINE (lesson + discipline + teacher), SWAP (lesson +
  another lesson of the same cohort — a later one, or for a future day also an earlier one; its discipline and
  teacher move into the slot), MERGE_SUBGROUPS (lesson + `swapLessonId` = the same discipline's lesson of another
  subgroup at the pair; its teacher takes both subgroups as one whole-group lesson in his room), CANCEL (lesson),
  ROOM_CHANGE (lesson + room), DISCIPLINE_FINISHED (lesson, reason FINISHED, optional discipline +
  teacher), ADDITIONAL (date, pairs, discipline, teacher), BLOCK (date, pair range, discipline, optional
  teacher; replaces every lesson of the cohort in the range). Ranges only for BLOCK and ADDITIONAL.
* **Validation on create** — the lesson must belong to the requested group/date/pair and still be
  SCHEDULED; dates of ADDITIONAL/BLOCK must be study days of a semester without a holiday or practice
  of the group (422); one open decision per lesson (409; a block may cover a lesson whose other
  decision only cancels it — order №35 has «1ТК1 1-4 ЗУ» and «1ТК1 1 Пари не буде»); hard conflicts of
  the lessons the change would create (409 `conflicts[]`, `DatedSlotChecker`): teacher, cohort,
  exclusive room or `maxParallel`, capacity, required room type, UNAVAILABLE; pending/confirmed
  changes count as occupying their slots, and a lesson another open change takes away (its original or
  moved/merged lesson, not a room change) no longer holds its teacher. Shift windows are not enforced (real orders put extra pairs
  outside them): any pair 1–8, also at publication and when a later timetable republication re-checks the
  lessons kept from changes (`ShiftWindowRule` skips dated lessons, round 7). Decisions about one group-day
  are serialized by `lockGroupDay`.
* **Substitute's room** (round 7, DOMAIN §5/§7) — REPLACE_DISCIPLINE without `newRoomId`: `ChangeCommands.create`
  asks `effect.SubstituteRooms.usualFreeRoom(lesson, teacherId, disciplineId, ownChangeId)`: the substitute's usual
  room for the discipline in the group (`TeacherRoomQueries.published` → `TeacherRooms.forGroup`) when it differs
  from the lesson's room and `DatedSlotChecker.roomConflicts` finds it free (occupancy with open changes, capacity,
  room type); then it becomes `newRoomId`. Otherwise `newRoomId` stays null and the lesson keeps its room — there is
  no warning field on `ChangeDto`, `new.room` shows the result. Online lessons keep their room. A given `newRoomId`
  wins. The recommender's `suggestedRoom` takes the room from the same `TeacherRooms.forGroup` (one statement for
  the whole pool), so the room offered in the options is the room `POST /changes` would choose. Test: `SubstituteRoomIT`.
* **Approval** (`ChangeWorkflow.open`, DOMAIN §7) — `substitution.approvalMode` decides for a change that
  assigns a teacher: NOTIFY (default) DRAFT → CONFIRMED, no deadline, `substitution.status-changed` plus
  `substitution.confirmation-requested` with `mode = NOTIFY` (the teacher is informed); CONFIRM DRAFT →
  PENDING_CONFIRMATION, deadline = now + `substitution.confirmTimeoutMinutes` (default 120), the request;
  AUTO DRAFT → CONFIRMED without a message. Without a teacher (CANCEL, ROOM_CHANGE, finished without
  replacement) always AUTO. The column `approval_mode` stores how the change was approved.
* **Transitions** (`ChangeStatusMachine`, illegal → 422 `CHANGE_ILLEGAL_TRANSITION`):
  DRAFT → PENDING/CONFIRMED/CANCELLED; PENDING → CONFIRMED/DECLINED/CANCELLED; CONFIRMED →
  APPLIED/CANCELLED; DECLINED → PENDING (ask again)/CANCELLED; APPLIED, CANCELLED final. CONFIRMED →
  DECLINED is not in the table: only the informed teacher of a NOTIFY change takes it
  (`ChangeWorkflow.declineAssigned`, `requireTeacherDecline`). Every transition writes `change_history`
  (creation is one entry `null → status`), publishes `substitution.status-changed` (into PENDING:
  `substitution.confirmation-requested` instead) and pushes SSE `change.updated` (ChangeDto) to ADMIN and to
  the assigned teacher after commit. A declined or cancelled change refreshes its group-day lessons
  (`DatedLessonRefresh`).
* **Teacher's answer** (`ChangeReplies`, cabinet and bot): only the assigned teacher (403). The answer takes
  the date lock first and only then reads the change (`findDateById`), so an answer racing with a publication
  sees the published state. CONFIRM request: only while PENDING (409). NOTIFY change: «Добре» on CONFIRMED or
  APPLIED → `teacher_answer = CONFIRM`, history entry with the same status on both sides, event
  `substitution.teacher-replied`; «Не можу» on CONFIRMED → DECLINED; «Не можу» on APPLIED → objection
  (`teacher_answer = DECLINE`, history APPLIED → APPLIED «… потрібне доповнення», `teacher-replied`), the order is
  never undone; the same answer again → 200 without a new entry or event; another answer after a decline or an
  objection, or on a cancelled change → 409. Actor in the history: the username, or «Кренцін М.Д. (Telegram)».
  `NotifyApprovalIT` covers every path, the race of «Не можу» with the publication and parallel answers with a
  cancel.
* **Dispatcher actions** — `confirm` instead of the teacher (DRAFT/PENDING; a declined change must be
  asked again or replaced: 422 `CHANGE_DECLINED`); `request-confirmation` starts a new round;
  `PUT` edits only the room and the reason (another teacher or discipline = cancel and create a new
  change, so the teacher who was asked gets «скасовано»), optimistic locking with `version`;
  `DELETE` cancels (idempotent; APPLIED → 422).
* **Reminders** — `ConfirmationReminders` runs every `app.substitution.reminder-check-interval`
  (PT1M): a pending change past its deadline gets `reminded_at` and the confirmation request is sent
  again (same change id, the passed deadline) plus SSE; exactly once per confirmation round
  (column + optimistic lock).
* **Absences** — overlapping absences of one teacher are rejected (409); cancelling an absence or
  shrinking it cancels its changes that are no longer needed; `absence.reported` carries no reason or
  comment; SSE `absence.created` / `absence.updated` carry `AbsenceDto.Summary` (no reason/comment).

## 4. Ranking (`OptionsGatherer` → `OptionRanker`, pure; DOMAIN §7)

* **Gathering** (`OptionsGatherer.forDay(date, lessons)`, the per-lesson endpoint is a day with one lesson): one
  query each for the day's lessons, open changes, absences, the weekday's preferences, declines
  (`OptionsDeclines`), the six-week cover ledger (+ one lineage lookup, `ChangeLineage` shared with the teacher
  statistics), weekly hours of the pool's teachers and the usual rooms; per group the load items and
  `AccountingQueries.progressOfGroup`; catalog names in three batch reads. Budget asserted in `OptionsGatheringIT`:
  ≤ 30 statements per lesson (28 measured, v1 had 49 in the same scenario), a day plan ≤ 15 + 8 per group.
* **Pool**: other disciplines of the group for the lesson's cohort, each by its own teacher (REPLACE_DISCIPLINE);
  plus MERGE_SUBGROUPS, SWAP, CANCEL. Never REPLACE_TEACHER or ADDITIONAL.
* **Order**: band (CLEAN, EASY_CANCEL, CAUTION, LAST) → kind → integer cost → oldest last cover → title. Costs,
  chips and the «recommended» rule are in DOMAIN §7; `RankingFactor` switches single factors off for the offline
  evaluation only.
* **Day plan**: `DayPlanner` (pure) over the inputs of one `forDay` read.
* **Manual list**: `TeacherAvailability` (8 statements).
* **Recommendation log**: `RecommendationLog` (change package) ranks the lesson before `POST /changes` and logs
  `substitution.recommendation …` after it succeeded.

## 5. Orders (DOMAIN §8)

* **Draft** (`GET /draft?date=`, advisory lock per date): rows of all CONFIRMED changes of the date
  (`OrderRowComposer`, pure), notes of the calendar periods active on the date (until the dispatcher
  edits them: `notes_edited`), signature from `order.signatoryPosition/Name` (default «Заступник
  директора з НР» / «Микола ПОЛІЩУК»), labels «ПОНЕДІЛОК» / «ЗНАМЕННИК». Rows stay untouched when the
  content did not change.
* **Rows**: REPLACEMENT, CANCELLATION («Пари не буде» on the right), ADDITIONAL («ДОДАТКОВО» on the
  left), BLOCK («1-4», the block on the left plus room) followed by AS_SCHEDULED rows («За розкладом»)
  for the group's other scheduled pairs (as order №105), FINISHED («Вичит.», right side only with a
  replacement), SWAP = replacement in the free slot + «Пари не буде» in the pair the lesson leaves,
  MERGE_SUBGROUPS (left: the absent teacher's subgroup «(п.)», right: the teacher who takes both, his room),
  ROOM_CHANGE in «ЗАМІНА АУДИТОРІЙ». Subgroups of one slot merge («Харченко О./Михальчук»,
  «304/309»), a single subgroup is marked «Крисак Л. (п.)». Order: course, group code (Ukrainian
  collation), pair. Texts: discipline short name, teacher display name, room name; reasons «Відп.»,
  «Зв.зам.», «Вичит.», «Відр.», OTHER = free text; BLOCK/ADDITIONAL/ROOM_CHANGE have none.
* **Header**: `title` «РОЗПОРЯДЖЕННЯ» (draft) → «РОЗПОРЯДЖЕННЯ №35»; amendment «ДОПОВНЕННЯ до
  розпорядження №35» (it takes the next number of the semester); `OrderDto.subtitle` «По коледжу про
  деякі зміни в розкладі занять», `OrderDto.dateLabel` «На 19 жовтня 2026 р.».
* **Publication** (one transaction): numbering lock (`pg_advisory_xact_lock` per semester) → the draft
  must equal the rows rebuilt from the current confirmed changes (otherwise 409 «Чернетка застаріла»)
  → `ChangePublication.apply`: plan all effects, cancel the replaced lessons, check the new ones
  (`conflicts[]` → 409, nothing published), insert lessons (origin CHANGE / ADDITIONAL / BLOCK,
  `change_id`), `SET CONSTRAINTS … IMMEDIATE`, changes → APPLIED with `order_id` → number = max + 1 →
  `order.published` (full `OrderPayload` with `semesterName`, `affectedGroups`, `affectedTeachers`),
  `schedule.changed` (ORDER_PUBLISHED), SSE `order.published` and `schedule.changed`.
* **Amendments**: after the main order of a date is published, newly confirmed changes go into one
  draft amendment with only these rows; an amendment without rows is not published (422
  `AMENDMENT_EMPTY`). With nothing new, `GET /draft` returns the latest published document.
* **Public changes**: rows of the published documents of the date (main first), grouped by group.

## 6. Accounting (DOMAIN §9)

Per group × discipline with load items: plan = Σ planned hours; lessons = SCHEDULED dated lessons;
ended = earlier date, or today after the bell of the semester's profile (without a profile today's
pairs are not ended); fact/remaining × `accounting.hoursPerPair` (default 2); forecast = fact +
remaining; FINISHED (fact ≥ plan), WILL_OVERRUN (> plan + 2), WILL_UNDERRUN (< plan − 2), ON_TRACK;
`forecastFinishDate` = lesson on which the cumulative hours reach the plan. Split disciplines are
counted per cohort (subgroup k + whole-group lessons) and the cohort with the largest forecast is
reported. Suggestions: FINISHED/WILL_OVERRUN → DISCIPLINE_FINISHED for the future lessons after the
finish lesson (ids listed); WILL_UNDERRUN → ADDITIONAL with the number of pairs. Teacher statistics
(confirmed and applied changes of the month): substitutions = taking a colleague's pair (REPLACE_*,
finished with replacement), hours = pairs × hours per pair; missed = own pairs taken over, moved away
(SWAP) or cancelled. Only teachers with activity are listed.

Numbering: `GET/PUT /api/admin/orders/numbering` (`OrderNumberingService`, table `order_numbering`, V1_602).
Without a row the semester starts at №1; `firstNumber` = 35 makes the first published order №35 (the
college's paper orders №1–34). The next number is max(last + 1, first); a first number at or below the last
used one is 422 `ORDER_NUMBER_USED` (numbers never go back or repeat).

## 7. Configuration

| Key | Default | Meaning |
|---|---|---|
| setting `substitution.approvalMode` | NOTIFY | NOTIFY (assign and inform), CONFIRM (ask and wait) or AUTO (assign silently) |
| setting `substitution.confirmTimeoutMinutes` | 120 | deadline of a confirmation round (CONFIRM only) |
| setting `order.signatoryPosition` / `order.signatoryName` | «Заступник директора з НР» / «Микола ПОЛІЩУК» | signature |
| setting `accounting.hoursPerPair` | 2 | academic hours per pair |
| `app.substitution.reminder-check-interval` (`SUBSTITUTION_REMINDER_INTERVAL`) | PT1M | reminder job period |

A stored setting of a wrong type fails loudly (IllegalStateException), it is never silently replaced.
Migrations: `V1_501` (`change.swap_lesson_id`, `change.reminded_at`), `V1_601`
(`order_document.notes_edited`, one draft amendment per date), `V1_505` (`approval_mode` NOTIFY,
`change.teacher_answer`), `V1_805` (the stored default `substitution.approvalMode` CONFIRM written by `system`
becomes NOTIFY; a value an administrator chose stays).

## 8. How to test

```bash
# unit + web slices of the area
mvn -q -pl services/core -am test -Dtest='OptionRankerTest,DayPlannerTest,ChangeStatusMachineTest,ChangeKindRulesTest,ChangeTest,HoursLedgerTest,OrderRowComposerTest,OrderTextsTest,StudyWeeksTest,UkrainianTextTest,*ControllerTest,*ControllersTest' -Dsurefire.failIfNoSpecifiedTests=false
# integration (Docker): lifecycle, order №35, numbering, absences → options → accounting
mvn -q -pl services/core -am verify -Dit.test='ChangeLifecycleIT,OrderPublicationIT,OrderNumberingIT,SubstitutionFlowIT,OptionsGatheringIT,ManualDecisionsIT' -Dtest='None*' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false
# offline evaluation of the ranking on order №35 (excluded from the normal build, report in target/replay-order35.txt)
python scripts/eval/export_replay_cases.py   # read-only, refreshes src/test/resources/replay/order35.json
mvn -q -pl services/core -am test -Dtest=OptionRankerReplayTest -Dtest.excludedGroups= -Dsurefire.failIfNoSpecifiedTests=false
```

`OrderPublicationIT` rebuilds the real order №35 of 19.10.2026: 34 orders already published since
01.09 → the draft prints the exact rows and header, publication assigns №35, applies the lessons and
sends `order.published` / `schedule.changed` through the outbox; the public endpoint shows the result.
Manual run: own database on the shared PostgreSQL, port 38081.

## 9. Known gaps

* REST security is tested with `@WebMvcTest` slices; the integration tests call services directly
  (a real JWT would need Keycloak or a second test context).
* `aiSpendToday` of the dashboard is always `null` (the importer accounts AI usage; core has no client for
  `GET /api/import/ai/usage`, the SPA can read it directly).
* Streams: a replacement or a cover keeps the stream of the replaced lesson; pending changes of other
  groups of the stream are exempt from the teacher check only through their original lesson.
* Recommender v2: the fairness factors (cover burden) have no real history yet; on order №35 v2 reaches RD
  hit@3 5/9 and hit@1 2/9 (v1: 2/9 and 1/9), see CHANGELOG round 7. Weights are frozen until ≥ 30 new decisions.
* `POST /api/admin/import/changes` (historic orders) is not implemented.
* Orders always print teacher display names; `showTeacherFullNames` is not applied here.
* Reminders reach the dispatchers through SSE; the admin Telegram chat depends on the notification
  service recognising a reminder (same change id, deadline before `occurredAt`).
* Calendar helpers, `LessonView` mapping and settings keys duplicate what the calendar, timetable and
  settings modules are building in parallel; reconcile at merge.
