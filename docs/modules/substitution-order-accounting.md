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
| `GET /api/admin/lessons/{lessonId}/options` | ADMIN | ranked options of a SCHEDULED lesson |
| `GET/POST /api/admin/changes`, `GET/PUT/DELETE /{id}`, `POST /{id}/request-confirmation`, `POST /{id}/confirm` | ADMIN | changes and their workflow |
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
  a later lesson of the same cohort; its discipline and teacher move into the slot), CANCEL (lesson),
  ROOM_CHANGE (lesson + room), DISCIPLINE_FINISHED (lesson, reason FINISHED, optional discipline +
  teacher), ADDITIONAL (date, pairs, discipline, teacher), BLOCK (date, pair range, discipline, optional
  teacher; replaces every lesson of the cohort in the range). Ranges only for BLOCK and ADDITIONAL.
* **Validation on create** — the lesson must belong to the requested group/date/pair and still be
  SCHEDULED; dates of ADDITIONAL/BLOCK must be study days of a semester without a holiday or practice
  of the group (422); one open decision per lesson (409; a block may cover a lesson whose other
  decision only cancels it — order №35 has «1ТК1 1-4 ЗУ» and «1ТК1 1 Пари не буде»); hard conflicts of
  the lessons the change would create (409 `conflicts[]`, `DatedSlotChecker`): teacher, cohort,
  exclusive room or `maxParallel`, capacity, required room type, UNAVAILABLE; pending/confirmed
  changes count as occupying their slots. Shift windows are not enforced (real orders put extra pairs
  outside them). Decisions about one group-day are serialized by `lockGroupDay`.
* **Approval** — a change that assigns a teacher waits for that teacher when
  `substitution.approvalMode` is CONFIRM (default): DRAFT → PENDING_CONFIRMATION, deadline =
  now + `substitution.confirmTimeoutMinutes` (default 60). Otherwise (AUTO, or no teacher: CANCEL,
  ROOM_CHANGE, finished without replacement) DRAFT → CONFIRMED. The column `approval_mode` stores
  how the change was approved.
* **Transitions** (`ChangeStatusMachine`, illegal → 422 `CHANGE_ILLEGAL_TRANSITION`):
  DRAFT → PENDING/CONFIRMED/CANCELLED; PENDING → CONFIRMED/DECLINED/CANCELLED; CONFIRMED →
  APPLIED/CANCELLED; DECLINED → PENDING (ask again)/CANCELLED; APPLIED, CANCELLED final. Every
  transition writes `change_history` (creation is one entry `null → status`), publishes
  `substitution.status-changed` (into PENDING: `substitution.confirmation-requested` instead) and
  pushes SSE `change.updated` (ChangeDto) to ADMIN and to the assigned teacher after commit.
* **Teacher's answer** (cabinet and bot): only the assigned teacher (403), only while PENDING (409,
  as CONTRACTS §4 says for the bot). Actor in the history: the username, or «Кренцін М.Д. (Telegram)».
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

## 4. Ranking (`OptionRanker`, pure)

REPLACE_TEACHER exactly as DOMAIN §7: candidate ≠ absent teacher, not absent, not busy (lesson or
pending/confirmed assignment in the slot), not UNAVAILABLE; +50 «Веде цю дисципліну» (competence or
any load item of the semester), +20 «Та сама циклова комісія», +15 «Закриває вікно між 2 і 4 парою»,
+10 «Вже має 2 пари цього дня», −8 per substitution this month (pending/confirmed/applied takeovers),
at most −24, chip «3 заміни цього місяця», −15 UNWANTED (warning «Небажаний час (побажання
викладача)»); top 10; ties: fewer substitutions, then name. Other kinds (documented base scores):
REPLACE_DISCIPLINE 40 + 20 «Відстає на N год» (plan − forecast) + day factors, finished disciplines
excluded, top 5, warning about a required room type; SWAP 45, +10 «Група піде раніше» when the moved
pair is the last one, −15 and a warning when it leaves a window; CANCEL 20, +15 for the first/last/only
pair, −10 with «У групи з'явиться вікно» in the middle; ADDITIONAL (make-up within 14 days, adjacent to
the group's day, inside the shift window, free for the group and the teacher) 15. Display score is
clamped to 0..100.

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
| setting `substitution.approvalMode` | CONFIRM | AUTO or CONFIRM |
| setting `substitution.confirmTimeoutMinutes` | 60 | deadline of a confirmation round |
| setting `order.signatoryPosition` / `order.signatoryName` | «Заступник директора з НР» / «Микола ПОЛІЩУК» | signature |
| setting `accounting.hoursPerPair` | 2 | academic hours per pair |
| `app.substitution.reminder-check-interval` (`SUBSTITUTION_REMINDER_INTERVAL`) | PT1M | reminder job period |

A stored setting of a wrong type fails loudly (IllegalStateException), it is never silently replaced.
Migrations: `V1_501` (`change.swap_lesson_id`, `change.reminded_at`), `V1_601`
(`order_document.notes_edited`, one draft amendment per date).

## 8. How to test

```bash
# unit + web slices of the area
mvn -q -pl services/core -am test -Dtest='OptionRankerTest,MakeUpSlotFinderTest,ChangeStatusMachineTest,ChangeKindRulesTest,ChangeTest,HoursLedgerTest,OrderRowComposerTest,OrderTextsTest,StudyWeeksTest,UkrainianTextTest,*ControllerTest,*ControllersTest' -Dsurefire.failIfNoSpecifiedTests=false
# integration (Docker): lifecycle, order №35, numbering, absences → options → accounting
mvn -q -pl services/core -am verify -Dit.test='ChangeLifecycleIT,OrderPublicationIT,OrderNumberingIT,SubstitutionFlowIT' -Dtest='None*' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false
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
* Streams: a replacement keeps the stream of a same-discipline lesson; pending changes of other
  groups of the stream are exempt from the teacher check only through their original lesson.
* `POST /api/admin/import/changes` (historic orders) is not implemented.
* Orders always print teacher display names; `showTeacherFullNames` is not applied here.
* Reminders reach the dispatchers through SSE; the admin Telegram chat depends on the notification
  service recognising a reminder (same change id, deadline before `occurredAt`).
* Calendar helpers, `LessonView` mapping and settings keys duplicate what the calendar, timetable and
  settings modules are building in parallel; reconcile at merge.
