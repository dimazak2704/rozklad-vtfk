# importer — imports, normalization and AI extraction

Service `services/importer` (port 8083, own database `importer`). It accepts files, parses them into a
**draft** with a **report**, matches the draft against the core catalog, lets the admin fix the matching
and **applies** the result through core's import endpoints. It never writes core data directly
(ARCHITECTURE §4). It also serves `POST /api/import/ai/parse-absence` for the Telegram bot and AI usage
accounting.

Specs: CONTRACTS §5 (endpoints), DOMAIN §10 (normalization), DOMAIN §11 (source formats).

## 1. Endpoints (gateway paths)

| Method & path | Role | Notes |
|---|---|---|
| `POST /api/import/jobs` multipart `file`, `kind`, `semesterId?`, `options?` (JSON text) | ADMIN | 202 + `Location`, job in `UPLOADED`; parsing is queued |
| `GET /api/import/jobs?kind&status&page=0&size=20` | ADMIN | page `{items,page,size,total}`, newest first; items without `draft`/`report` |
| `GET /api/import/jobs/{id}` | ADMIN | full `ImportJobDto` |
| `PUT /api/import/jobs/{id}/mappings` `{mappings:[{type,raw,targetId?,createNew?,addAlias}]}` | ADMIN | merges decisions, re-matches against a fresh catalog, returns the job; `createNew` for a value the kind cannot create (an order; a group of a program/plan; a program discipline named only by its file) → 400 `errors[mappings[i].createNew]` |
| `PUT /api/import/jobs/{id}/options` `{version, options}` | ADMIN | replaces the application parameters (groups, weekly hours, version name, calendar codes/groups/titles/rooms; `sheet`/`semesterNumber`/`academicYear` → 400, they decide how the file was read), rebuilds the report; stale `version` → 409 |
| `POST /api/import/jobs/{id}/apply` optional body `{semesterId?}` | ADMIN | 202, job in `APPLYING` (or unchanged when already `APPLYING`/`APPLIED`); the semester is not needed for `ORDER_DOCUMENT` and `ACADEMIC_CALENDAR_XLSX` |
| `POST /api/import/ai/parse-absence` `{text, teacherId?, today}` | ADMIN, SERVICE | rules first, AI only when unsure |
| `GET /api/import/ai/usage` | ADMIN | today's calls/tokens/cost, daily budget, models |

`ImportJobDto` = CONTRACTS shape `{id, kind, fileName, status, createdAt, summary, report, draft, ai?, error?}`
plus additive `fileType, fileSize, updatedAt, createdBy, semesterId, options, mappings, result, canApply`.
`ReportRow` = `{severity, type, raw, matched?, suggestions, location}` plus an additive Ukrainian `message`.
`type` is one of `TEACHER, DISCIPLINE, GROUP, ROOM` (catalog matching, mappable) or `LESSON, CELL, BELLS,
HOURS, ORDER, CURRICULUM, CATALOG, FILE, OPTIONS, PERIOD_KIND, PERIOD` (parse remarks, readiness; the last two
belong to the academic calendar).

Errors are ProblemDetail like the rest of the system: 400 `validation` (`errors[]`, e.g. `options.semesterNumber`),
404, 409 `conflict` (job state, concurrent change `stale-data`), 413 `payload-too-large`, 415 `unsupported-file`,
422 `business-rule` with `code` (`IMPORT_NEEDS_REVIEW`, `IMPORT_FAILED`, `SEMESTER_REQUIRED`, `GROUPS_REQUIRED`,
`AI_DISABLED`), 429 `ai-budget-exceeded` (`spentUsd`, `dailyBudgetUsd`), 503 `service-unavailable` (core catalog
down while saving mappings).

## 2. Job lifecycle

```
UPLOADED ──parse──► PARSING ──► NEEDS_REVIEW ◄──mappings──► READY ──apply──► APPLYING ──► APPLIED
                        └──► FAILED (parse error)                     │            └──► FAILED (step error, retry allowed)
                                                                      └── catalog changed ──► NEEDS_REVIEW
```

* **Upload** checks size (≤ `app.import.max-file-size`, 20 MB), sniffs the real type by magic bytes / OOXML
  entry names / OLE2 `WordDocument` stream (never the extension; OOXML names come from the zip's central directory,
  nothing is inflated, so a zip bomb costs the upload request nothing; only a cut package without a central
  directory is read entry by entry, inflating at most 32 MB, `FileTypeSnifferTest`), validates `options`, checks AI availability and
  the daily budget when the kind needs AI, stores the bytes (DB `bytea` or a volume directory) and publishes
  `ImportJobQueued`; after commit the job runs on a bounded pool (`app.import.workers`, default 2) with the
  MDC of the request that queued it (MDC `requestId` and `importJob`), so its calls to core forward the same
  `X-Request-Id`; jobs re-queued after a restart get none.
* **Parse** (no transaction held): handler → draft + parse remarks; then catalog snapshot from core
  (`GET /api/internal/catalog/snapshot`) → matching rows. If core is down the report gets one blocking
  `CATALOG` row and matching is repeated by saving mappings (even an empty list).
* **Status** follows the report: any blocking row (ERROR, or an entity with suggestions but no decision) →
  `NEEDS_REVIEW`, otherwise `READY`. The report is sorted: errors, undecided, warnings, info, matched.
* **Mappings**: `targetId` maps to an existing entity (must exist in the snapshot), `createNew` (object, may be
  empty or override `shortName`, `name`, `displayName`, `lastName`, `code`) confirms a new entity, neither
  removes the earlier decision; `addAlias` (teachers/disciplines with `targetId`) stores the file spelling as an
  alias. Raw values must belong to the job (else 400). Optimistic version check → 409 on concurrent edits.
* **Apply** re-reads the catalog, re-matches (new blocking rows → back to `NEEDS_REVIEW` with a message), then
  runs the kind's steps. Every finished step is stored in `result` at once (checkpoint); a retry after a failed
  step skips finished ones. Saving mappings, options or a revised draft clears the checkpoints of a job that is
  not applied yet: the stored steps were built from the old inputs, so the next apply sends every step again
  (core's catalog import is an upsert; a new teacher or discipline the admin confirmed after a rejection is
  then really created). Calls carry `Idempotency-Key: import-job:<id>:<step>`. A second apply of an applied
  job changes nothing.
* **Core already holds the data**: `catalog` is the only step that is not a kind's last one (`timetable`,
  `changes`, `load`). Once a last step is stored, core has the version, order or load and refuses that key with
  another body (409), so edits of mappings, options and the draft answer 409 ("press apply to finish") and the
  next apply only marks the job `APPLIED` with the stored result, without the catalog and without calling core.
  Fixes after that are made in core (decision: the importer never re-sends data core already accepted).
* **Restart**: `UPLOADED` jobs are re-queued; `PARSING` → `FAILED` (re-parsing could loop on a poison file and
  re-spend AI money); `APPLYING` → `FAILED` with a "press apply again" message (checkpoints make it safe).

## 3. Kinds

| Kind | Files | Options | Draft | Apply (core) |
|---|---|---|---|---|
| `TIMETABLE_XLSX` | XLSX | `sheet?` (default «Розклад друк»), `versionName?` | `TimetableDraft`: groups, teachers, disciplines, rooms, lessons, bell profiles | 1. `POST /api/admin/import/catalog` (new entities + aliases) 2. `POST /api/admin/import/timetable` → draft version |
| `DISCIPLINE_PROGRAM` | DOCX, DOC, PDF | `groupCodes` (for apply), `hoursPerWeek?` | `ProgramDraft`: name, cycle commission, hours, semester (informative), hours/week, credits, source | 1. catalog (new discipline) 2. `POST /api/admin/import/load` one item per group |
| `CURRICULUM` | PDF scan, JPEG/PNG/WEBP, XLSX | `semesterNumber` (required), `groupCodes` (for apply) | `CurriculumDraft`: rows of that semester | 1. catalog (new disciplines) 2. load items per group × row |
| `ORDER_DOCUMENT` | DOCX; JPEG/PNG/WEBP/GIF/PDF via AI | — | `OrderDraft`: number, kind, date, weekday/parity labels, rows of both sections, notes, signature | `POST /api/admin/import/changes` (text snapshots) |
| `ACADEMIC_CALENDAR_XLSX` | XLSX | `sheet?`, `academicYear?`, `skipCodes?`, `skipGroups?`, `titles?`, `rooms?` | `AcademicCalendarDraft`: groups, legend, runs; codes, periods, groupSemesters and core's dry-run `check` (rebuilt with every report) | one step `calendar`: `POST /api/admin/import/calendar-periods` (re-sendable upsert by source key) |

### TIMETABLE_XLSX parser (DOMAIN §11, tested on the real file)

Structure is found by content, not by fixed rows or colours (the real file differs from DOMAIN's row numbers:
course labels of courses 2–3 are empty merged cells, the curators row of courses 2–4 has the header colour).
The scans go cell by cell, so a sheet is bounded first (`SheetGrid`): at most 2 million cells in the used area,
50 000 merged ranges and 500 000 merged cells inside it; merges are indexed only inside the used area. A bigger
sheet stops the import with «Аркуш «…» завеликий для розкладу…» (a bell sheet that big is skipped with a warning;
while looking for the timetable sheet such sheets are skipped, at most 30 sheets are tried). The real file is about
640 × 160 cells with 3 200 merges (`SheetGridLimitsTest`). Before this a few kilobytes of XML with one merge over
the sheet or a cell at `XFD1048576` exhausted the heap or held a worker for hours.

* **group header row** = a row with ≥ 3 group codes in the first column of 3-column blocks, mostly starting with a
  digit (so a lesson row with «ІГ», «ІКТ» never becomes a header); home room = the block's third cell; curators
  = the row above (`«Шароварська С.П. (Погосян Т.В.)»` → curator + acting curator, with a warning);
* **day blocks** = weekday labels in column A (merged ranges or plain cells); **pairs** = numbers in column B
  («1.», «3»), two rows per pair (a single row at the end of a block is a whole-week pair);
* **two-row semantics** via merged ranges: discipline merged over both rows → every week; then different
  teachers per row → subgroups 1/2 (rooms per row, merged room shared); same teacher with different rooms →
  numerator/denominator rooms; not merged → top = NUMERATOR, bottom = DENOMINATOR; «Крисак Л./Можаровська» with
  «110/12» → subgroups 1/2 with their rooms (also inside a numerator/denominator row); «(п)» → one-subgroup
  lesson (subgroup 1, warning: the number is not in the file); «(гр)»/«гр.» → explicit whole group;
  «Укр.мов.Зоріна», «Матем.Овчар Ін.» → the surname is cut from the discipline cell;
* **shift** of a group = the window holding most of its lessons (shift 1: pairs 1–4; shift 2: 5–8 Mon–Thu,
  4–6 Fri); lessons outside → warning. Result on the real file: courses 1, 3, 4 shift 1, course 2 shift 2;
* **bell sheets** («дзвінки (2 зм)», «дзвінки»): tables «N. | HH:MM-HH:MM» anywhere, titled «ПОНЕДІЛОК-ЧЕТВЕР» /
  «П'ЯТНИЦЯ» (no title = all days), grouped by pair length → «2 зміни, 60 хв», «2 зміни, 50 хв», «1 зміна, 80 хв»;
* **clash warnings** inside the file: group cohort overlaps, a teacher in two places (same discipline + room →
  "probably a stream", info), rooms over `maxParallel`. The real file has ~60 room clashes between the 3rd and
  4th course (the 3rd course studied online) and one real teacher clash.

Real file result: 56 groups (16/15/15/10), 1077 lessons, 108 teachers, 127 disciplines, 45 rooms; no errors.

### Consolidation inside one file (teachers, disciplines, rooms)

* **Teachers**: curators give full initials, lessons mostly bare surnames. A rare spelling (≤ 2 uses) one edit
  away (OSA distance 1, ≥ 5 letters, not a masculine/feminine pair, digits equal) from a frequent one (≥ 3 uses)
  is a typo: «Граболінська» → «Гарболінська», «Герасменко» → «Герасименко», «Хорченко О.» → «Харченко О.»,
  «Вернигорв» → «Вернигора», curator «Шидлорвська Т.І.» → «Шидловська». Two frequent spellings («Марценюк» 16 /
  «Марцинюк Т.» 12) and gender forms («Шидловська»/«Шидловський», «Поповська»/«Поповський») stay apart. Same
  surname + compatible initials (prefix per initial group: «О.» ~ «О.А.», «Ін.» ≠ «Ів.») = one person. A bare
  surname matching several persons («Харченко») goes to the one teaching the same discipline elsewhere in the
  file (ФК → Харченко Г.Ю.), otherwise a separate record marked `needsReview`. Short name = surname + full
  initials when known («Вихристюк Ю.А.»), else as written («Кренцін»); display name gets an initial only when
  the surname is shared («Овчар Ін.», «Овчар Ів.»).
* **Disciplines**: one abbreviation key (`Укр.мова.` = `Укр.мова`, `Фк` = `ФК`); rare typos are merged
  («Матет.» → «Матем.», «Вм.іст.» → «Вс.іст.», «Маи.спц.» → «Мат.спц.»); truncations of *word* abbreviations
  are merged when unambiguous («Укр.мов» → «Укр.мова», «Інфом.» → «Інф.», «Екол.» → «Еколог.», «Вс.» → «Вс.іст.»
  by the same teacher). Acronyms are never merged by prefix («ВСТР» ≠ «ВСТРС», «ІГ» ≠ «ІГС»). Close word
  abbreviations that are not merged are reported («Вс.спц.» / «Вст.спц.»). Canonical short name = the most
  frequent spelling; full name only from the certain list of DOMAIN §10 (plus «НДЕКС» = НДЕКСМ), otherwise the
  abbreviation with `needsReview` — names are never invented.
* **Rooms**: digits («6.» → «6», «ауд. 307» → «307»), «с.з», «С.З.» → «с.з.» (GYM, maxParallel = max(3, seen)),
  «с.п.» → SPORTS_GROUND.

### Matching against the catalog (DOMAIN §10)

`NormalizationEngine` over `CatalogPort` (core REST in production, stubs in tests):

* teachers: alias / short name / display name text → surname + compatible initials (bare surname with one
  candidate is a match) → Jaro-Winkler ≥ 0.92 on the surname (compatible initials, no gender pairs) as
  **suggestions only**;
* disciplines: abbreviation key of short name or alias → full name (draft name or the certain list) → close
  names (JW ≥ 0.92) as suggestions;
* groups by canonical code («4КИ1» = «4КІ1»), rooms by canonical name.

Outcomes: MATCHED (OK), MAPPED by admin (OK), NEW (INFO, created on apply), AMBIGUOUS (WARN + suggestions,
blocks apply), INVALID_MAPPING (ERROR). Fuzzy matches never bind automatically. A new discipline whose name is
longer than 64 characters (core `short_name`) needs a mapping or `createNew.shortName` (ERROR row). For orders
matching is informative only (text import), so ambiguities do not block.

### DISCIPLINE_PROGRAM

DOCX via XWPF (cells expanded by `gridSpan`, at most 63, Word's column limit; a crafted span of two billion
filled the heap, as it did in the order parser), DOC via HWPF (`poi-scratchpad`), PDF via PDFBox (text only).
The structure table is found by header labels (bottom-most non-empty label per column: Всього, На заняттях,
СРС, Лекції, Практ., Лабор., Семінар., Іспит, Залік, Конс.); the first numeric row is the data row («90 (в тому
числі 30-підготовка…» → 90, «-» → empty, «+»/«х»/a digit in Іспит/Залік → yes). Checks (warnings): total ≠
classroom + self-study; classroom ≠ lectures + practice + labs + seminars; «відведено N годин» in the text or
«Загальна кількість годин - N» ≠ table total; per-topic plan sums ≠ structure; no exam and no credit. The
printed semester is shown as INFO and never used. Hours per week and credits are read from the description
table («аудиторних - 3», «Кількість кредитів - 5»). PDF without tables: the data line is used only when it is
unambiguous (7 numbers that add up); otherwise, and when no table exists at all, the **text model** extracts the
hours (`PROGRAM_HOURS`), with a warning; AI unavailable → ERROR row (the admin enters hours manually).

Real files: economics 150/60/90, 30 lect + 20 lab + 10 sem, exam, semester 7, 3 h/week, 5 credits, warning
«відведено 180 годин» vs 150; cybersecurity (DOC) 120/40/80, 20 + 20 labs, credit; НДЕКСМ (DOC) 90/40/50,
20 + 20 labs, credit, «семестр 5» (outdated) — the curriculum says 24 + 20 = 44.

### ORDER_DOCUMENT

DOCX: header paragraphs (number with spaced letters, «ДОПОВНЕННЯ до розпорядження №N», «На 19 жовтня 2026 р.»,
«ПОНЕДІЛОК - ЗНАМЕННИК»), the table found by «Група»/«Пара» headers (grid spans expanded; two lines in a cell
joined with « / »), «ЗАМІНА АУДИТОРІЙ» switches to ROOM_CHANGE rows, paragraphs after the table are notes, the
last «…директора…<tab>Ім'я ПРІЗВИЩЕ» line is the signature. Row types per DOMAIN §8 (range → BLOCK, «ДОДАТКОВО»
or empty left → ADDITIONAL, «За розкладом» → AS_SCHEDULED, «Пари не буде» → CANCELLATION, «Вичит.» without a
replacement → FINISHED, a right side «Об'єднання підгруп: Ін.мова (Можаровська) — обидві підгрупи» (the system's own
DOCX) → MERGE_SUBGROUPS with `toDiscipline` «Ін.мова» and `toTeacher` «Можаровська», else REPLACEMENT; «Вичит.»
without a replacement wins over a pair range). The vision schema and prompt offer MERGE_SUBGROUPS too; an answer or a
reviewed row that types the merge sentence otherwise follows the text with a WARN, and the sentence is split into
discipline and teacher. Photos/scans/PDF go
to the vision model with the same rules in the prompt; the answer is validated (unknown row types re-derived with a
warning, a FINISHED that contradicts the text — or a «Вичит.» row typed otherwise — replaced with the derived type and a
warning, dates parsed leniently, group codes canonicalized, model warnings copied into the report). An AI draft
carries a blocking ORDER row until the admin saves the reviewed rows. On parse and on every revision: a printed
weekday that is not the date's weekday, and any U+FFFD (unreadable character) in a row, the header, notes or the
signature are blocking ERROR rows; a row type sent by the review that contradicts the «Вичит.» rule follows the text
with a WARN (as for AI answers). Core refuses a printed parity that contradicts its calendar (422
`ORDER_IMPORT_LABEL_MISMATCH`), which returns the job to review with the message. Matching rows of an order are
informative and never promise «буде створено» (the order is stored as text). The admin saves the reviewed rows (`PUT /api/import/jobs/{id}/draft`, the SPA's
«Звірити рядки»); applying a reviewed draft sends `replace: true`, so a corrected re-import replaces an earlier
import of the same paper order in core.

### CURRICULUM

Vision model on the scan (all pages in one PDF document block) or the text model on an XLSX converted to
tab-separated text; only `options.semesterNumber` is extracted. Checks: hours/week × weeks vs semester hours,
lectures + practice + labs vs classroom hours, sum of hours/week vs the «Всього» row, rows without weekly hours
skipped, no rows → ERROR. See `importer-ai-samples.md` for the live run on `seed/curriculum-ki-2023.pdf`.
A plan names disciplines in full while the catalog holds the timetable's short forms, so a plan discipline that is not
in the catalog is never created on its own: until the admin maps it or confirms `createNew`, its row is an ERROR
(`createNeedsConfirmation`). Elective placeholders («Вибірковий освітній компонент 1», «ВК 2», «… за вибором») get
their own message: map them to the elective the group actually studies. Two rows that resolve to one discipline
(both mapped to the same catalog entry, two new ones with the same name, or one row read twice) are a blocking ERROR
naming both rows: core keeps one load item per group and discipline and rejects the pair at apply. Whether such
rows should instead be merged by summing their hours is an owner question; until it is answered nothing is summed.

### ACADEMIC_CALENDAR_XLSX («робочий графік освітнього процесу»)

Option Б step 1б of `Docs/Нотатки/Періоди навчального процесу — проєкт.md`; the request and response of core's
endpoint are in CONTRACTS («Calendar periods import»). Code: `calendar/AcademicCalendarParser` (file),
`CalendarPlan` (request, pure), `NoteTemplate` (order notes), `AcademicCalendarImportHandler`.

* **Parser** (POI, structure by content): the header cell «ГРУПА» gives the group column; of the next 8 rows the
  pair whose columns hold day numbers 6 days apart (31/6, 7/13, …) for the longest run of columns gives the weeks.
  Dates are rebuilt from the academic year (`options.academicYear`, else «на 2026 - 2027 навчальний рік» above the
  header, else the sheet name; none → parse error): the first week is the Monday nearest to 1 September with those
  day numbers, every next column is 7 days later and must match its cells (else «Тижні графіка йдуть не підряд»).
  Header cells left of the weeks with «тижн» / «іспит» give theory weeks and exams of semesters I/II (that cell and
  the next; empty → `null`, out of 1..53 / 0..20 → warning, `null`). Every row whose group cell is a group code
  after removing dashes («4КІ-1» → «4КІ1») is a group (a duplicate → warning, the first is kept). Codes are folded
  (upper case, Latin look-alikes → Cyrillic). A merged cell over several weeks gives its code to each. Equal
  adjacent codes of a group form a **run** from Monday of its first week to **Friday** of its last («НС» 16.11–29.11
  in the grid → 16.11–27.11). Legend = short upper-case codes with a description in one of the next three cells
  below the groups; when the legend has several lines for a code (НП), the cell's fill colour picks the line's
  title in sentence case (FFFF6600 → «Навчальна радіомонтажна практика»), an unknown colour the uncoloured line
  («Навчальна практика»), and a different colour starts a new run. Sheet size is bounded like `SheetGrid`.
  Real file: 56 groups, 11 codes (І А ВП Д ЗУ К НП НС ПП Р С), weeks 31.08.2026–29.08.2027, no warnings.
* **Report rebuild** (`ImportHandler.review`, on parse, mappings, options and right before apply): groups (minus
  `skipGroups`) and the rooms of `rooms` for codes in the file are GROUP/ROOM entities that this kind never
  creates (ERROR until mapped or skipped). Codes are matched to the snapshot's `periodKinds` by code or alias
  (`ЗУ`, alias `З`). Unknown code → ERROR `PERIOD_KIND` «Невідомий код «Х» у груп 4КІ1, 4КІ2: додайте вид у «Видах
  періодів» або пропустіть код.»; inactive kind → ERROR; a kind with effect ONLINE → ERROR «Вид «ФН» з ефектом
  «онлайн» не імпортується з графіка (у графіку немає режиму навчання): пропустіть код і додайте такий період у
  календарі вручну.» (core would refuse it without a study mode); a skipped code → INFO; an older core without kinds →
  one ERROR. Title: the legend's colour title, else `titles[code]`, else the kind's name; room: `rooms[code]`, only for
  SUSPEND kinds (else an INFO). Defaults when `titles`/`rooms` are absent: «НС» → «Основи національного
  спротиву», room «401» (owner, 15.11.2026); `{}` means none. Runs with the same kind, title, dates and room are one
  period; scope ALL when its groups are all active catalog groups, COURSES when exactly the active groups of some
  courses (at least 2 groups), else GROUPS. `orderNote` from the kind's `noteTemplate` (`NoteTemplate`; the shared
  test vectors are in `NoteTemplateTest`); `sourceKey` = `2026/2027|НС|2026-11-16|2026-11-27|<8 hex of SHA-256 of
  the title>`. `groupSemesters`: two per group (semesters 1 and 2). When nothing blocks, core is asked for a dry-run
  (no Idempotency-Key); its answer is stored as `draft.check` and becomes rows: `conflicts` → ERROR `PERIOD`
  («message (dd.MM.yyyy, пара N)»), `outOfSemester` → WARN «НС 4ЕА1 11.01–22.01 поза семестрами системи: не
  імпортується» / «… обрізано до 01.09–04.09», `manual` with differences → WARN, `manualOverlaps` → INFO,
  `groupSemesters.skipped` → WARN, counts → INFO «Буде створено N, оновлено M, видалено K, без змін L.», lesson
  counts → INFO; core down, 404 (an older core) or 400 → a blocking ERROR with the reason.
* **Apply**: the same request with `dryRun=false` and `Idempotency-Key: import-job:<id>:calendar`; a second apply
  of an applied job sends nothing. Core's 409 `conflicts[]` (a live change that appeared after the check) sends
  the job back to `NEEDS_REVIEW` with one ERROR row per conflict (`ImportHandler.rejectionRows`).

### parse-absence

`AbsenceTextParser` (rules): «сьогодні», «завтра», «післязавтра», weekdays («у понеділок»), «4.11», «04.11.2026»,
«4 листопада», ranges «з 4 по 6 листопада», «9-11 листопада», «з 10.11 до 12.11»; a date without a year that passed
more than 14 days ago is next year's; an explicit year is kept («1 січня 2030»). Weekday ranges «з понеділка по
середу» (genitive forms too; each weekday is the next one after the previous), day lists «11 і 12 листопада». The
answer is one range with one set of pairs: separate days with a study day between them, different pairs on
different days («10 листопада 3 пара, 11 листопада 1 пара») and pair numbers outside 1–8 get a clarification and
confidence 0.4 and the AI is not asked (it answers in the same shape). `today` must lie in 2000–2100 (400). Pairs: «1-2 пари», «з 1 по 3 пару», «2 і 3 пари», «4 пару», ordinals «на
першій парі», «першу і другу пари»; «весь день» → whole day (`pairs` null); no pair numbers also means the whole day, except when the text names only part of
the day («запізнюсь», «не встигну на початок занять», «після обіду», «піду раніше», «зранку»): then the answer asks
«на яких парах» and the confidence drops by 0.35, so the text model is asked. Reasons: лікарняний/хворію/приболів
→ SICK_LEAVE, відпустка → VACATION, відрядження/конференція → BUSINESS_TRIP, сімейні/особисті обставини →
PERSONAL, else OTHER. Confidence starts at 1 and drops when the date is assumed (−0.45), the reason is unknown
(−0.1), the text is uncertain («можливо») or the range was reversed. Below **0.7** the text model is asked
(`ABSENCE_TEXT`); its answer is validated (dates ordered and within a year, pairs 1–8) and used if more confident.
AI problems (disabled, budget, outage) never fail the call — the bot gets the rule answer with a clarification.
The text is never logged (absence reasons are private).

## 4. AI

* **Port** `AiExtractionPort.complete(AiRequest) → AiResponse` (JSON text, model, tokens, stop = COMPLETE |
  REFUSED | TRUNCATED). Adapters: `AnthropicAiAdapter` (official SDK `com.anthropic:anthropic-java` 3.6.0,
  Messages API with structured outputs `output_config.format` = JSON schema, explicit `output_config.effort`,
  thinking left at the model default, base64 image/PDF blocks before the instruction, content read by block type,
  refusal and `max_tokens` checked), `FakeAiAdapter` (`app.ai.provider=fake`: scripted answers per purpose, fails
  loudly when nothing is scripted), `DisabledAiAdapter` (no key).
* **Service** `AiExtractionService`: typed operations, prompts (`AiPrompts`, Ukrainian; documents and user text are
  marked as data, not instructions), schemas (`AiSchemas`: every object `additionalProperties:false`, all
  properties required, optional values `anyOf [type, null]`; ranges validated in Java), model choice (vision
  model only when documents are attached), budget check before each call, usage row after each call — also for
  refusals, cut-offs and invalid JSON (those tokens are billed). Before any call `AiDocumentPreparer` opens the
  file: a damaged image or a «PDF» that is not one fails with a plain message (no tokens); images over 5 MB or with a
  side over 8000 px (the API limit) are decoded with subsampling and downscaled to JPEG ≤ 2000 px. API rejections are
  reported in plain words (the file could not be read / the service settings), details only in the log.
* **Models** `AI_MODEL_TEXT` (default `claude-haiku-5-5`, effort `low`) and `AI_MODEL_VISION` (default
  `claude-sonnet-5-5`, effort `medium`); `AI_MAX_TOKENS` 16000; `AI_TIMEOUT` 180 s; `AI_MAX_RETRIES` 2. The
  parse-absence call (`ABSENCE_TEXT`) is interactive: the bot's importer client gives up after 20 s, so it uses
  `AI_INTERACTIVE_TIMEOUT` (10 s) and no retries; on a timeout the rule-based answer is returned in time.
* **Usage and budget**: table `ai_usage` (Kyiv date, purpose, model, tokens, cost, outcome, job). Cost =
  configurable price table `app.ai.prices` (USD per million tokens; Haiku 5.5 0.10/0.50 with the long-context tier
  0.50/2.50 above 100k input tokens, Sonnet 5.5 2/10, Opus 5.5 4/20; unknown models use `app.ai.default-price`
  10/50 so the budget is never under-counted). `AI_DAILY_BUDGET_USD` (default 2.00): checked before every call;
  an upload that needs AI gets **429** at once; calls in flight may finish over the budget (bounded overshoot);
  budget 0 disables AI calls. The job's `ai` sums all its calls.
* **Refusal fallback**: not enabled. Server-side `fallbacks` needs the beta endpoint and only re-routes
  cyber/frontier-LLM declines, which do not occur for college documents; the model ids are configurable (Haiku has
  no server-side fallback). A refusal fails the job with «Модель відмовилася обробити цей документ. Внесіть дані
  вручну.» and is counted.

## 5. Configuration

| Env | Default | Meaning |
|---|---|---|
| `ANTHROPIC_API_KEY` | empty | empty → AI disabled (deterministic parsers still work) |
| `AI_PROVIDER` | `anthropic` | `fake` only for tests |
| `AI_BASE_URL` | empty | API base URL override (tests) |
| `AI_MODEL_TEXT` / `AI_MODEL_VISION` | `claude-haiku-5-5` / `claude-sonnet-5-5` | |
| `AI_EFFORT_TEXT` / `AI_EFFORT_VISION` | `low` / `medium` | |
| `AI_DAILY_BUDGET_USD` | `2.00` | |
| `AI_TIMEOUT`, `AI_MAX_RETRIES`, `AI_MAX_TOKENS` | `180s`, `2`, `16000` | |
| `AI_INTERACTIVE_TIMEOUT` | `10s` | parse-absence only, never retried (the bot waits 20 s) |
| `IMPORT_MAX_FILE_SIZE` | `20MB` | the servlet limit is `UPLOAD_MAX_FILE_SIZE` 21MB so the service answers 413 itself |
| `IMPORT_STORAGE` | `DATABASE` | or `FILESYSTEM` with `IMPORT_STORAGE_DIR` (a volume; atomic writes) |
| `IMPORT_WORKERS`, `IMPORT_QUEUE_CAPACITY` | `2`, `50` | full queue → job FAILED «Черга обробки переповнена» |
| `CORE_URL`, `IMPORTER_CLIENT_SECRET`, `AUTH_TOKEN_URI` | foundation | catalog snapshot and import calls use the `rozklad-importer` service account |

## 6. Persistence (Flyway `V1__import_jobs_and_ai_usage.sql`, `V2__academic_calendar_kind.sql`)

`import_job` (kind/status/file metadata, sha256, storage, semester, JSONB `options`, `draft`, `issues` (parse
remarks), `report`, `summary`, `mappings`, `ai`, `result`, `error`, timestamps, optimistic `version`),
`import_file` (bytea; FK deferrable so file and job are written in one transaction in any order), `ai_usage`.
Entities use Jackson 3 for JSON columns (foundation's `Jackson3JsonFormatMapper`).

## 7. Decisions

* Structure of the workbook is detected from content and merged ranges, never from colours or fixed rows.
* Within-file merges are conservative (frequency + edit distance + gender guard); catalog matching never binds a
  fuzzy candidate — it only suggests. Wrong merges are worse than duplicates the admin can map.
* «(п)» without a number → subgroup 1 with a warning (the file does not say which subgroup).
* Curator with a name in parentheses: the first is the curator, the second is kept as `actingCurator` (warning).
* Specialties are sent as `{letters}` only: the Excel has no specialty names, nothing is invented.
* Bell profiles are part of the draft but not applied: CONTRACTS has no import endpoint for them.
* Orders are imported as text snapshots (`/api/admin/import/changes`), so their entity matching is informative.
* Apply is asynchronous (202) with per-step checkpoints and idempotency keys; parse is never retried
  automatically after a crash (cost and poison files).
* parse-absence never fails because of AI; 429 for the budget is returned where AI is mandatory (uploads); its
  AI call has its own short timeout and no retries so the fallback reaches the bot before the bot gives up.
* `\b` is not used in regexes: since JDK 19 it is ASCII-only and never matches next to Cyrillic.

## 8. Requests expected by core (for the core import agent)

The element shapes are not fixed by CONTRACTS; the importer sends (nulls omitted):

```
POST /api/admin/import/catalog
{ departments: [],
  specialties: [{letters}],
  teachers:    [{shortName, displayName, lastName, needsReview}],
  disciplines: [{shortName, name, needsReview}],
  rooms:       [{name, type, maxParallel}],
  groups:      [{code, course, specialtyLetters, number?, shift, curatorShortName?, homeRoomName?}],
  aliases:     [{type: TEACHER|DISCIPLINE, alias, targetShortName}] }          → any JSON (stored as result.catalog)
POST /api/admin/import/timetable {semesterId, name, lessons:[{weekday, pair, parity, groupCode, subgroup,
  disciplineShortName, teacherShortName?, roomName?, online}]}                  → e.g. {versionId} (result.timetable)
POST /api/admin/import/load {semesterId, items:[{groupCode, disciplineShortName, lessonKind: MIXED, hoursPerWeek,
  plannedHours, controlForm: EXAM|CREDIT|NONE, source: PROGRAM|CURRICULUM, note}]}
POST /api/admin/import/changes {date, number?, kind, parentNumber?, weekdayLabel?, parityLabel?, items:[{section,
  groupCode, pairLabel, rowType, fromDiscipline?, fromTeacher?, reasonText?, toDiscipline?, toTeacher?, roomText?}],
  notes, signatoryPosition?, signatoryName?}
POST /api/admin/import/calendar-periods[?dryRun=true] {academicYear, periods:[{sourceKey, kindCode, title, startsOn,
  endsOn, scope, groupCodes, courses, roomName, studyMode, orderNote}], groupSemesters:[{groupCode, semesterNumber,
  theoryWeeks, exams}]}                                                        → result.calendar
Header on all real runs: Idempotency-Key: import-job:<jobId>:<step> (none on the calendar dry-run)
```

Upserts must keep existing values the request does not carry (e.g. a specialty name). Lessons reference only
entities that exist in the catalog or are in the same import's catalog request.

## 9. How to test

```bash
source scripts/env.sh
mvn -q -pl services/importer -am test                                    # unit tests (real seed files)
mvn -q -pl services/importer -am verify                                  # + Testcontainers ITs
mvn -q -pl services/importer -am verify -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false \
    -Dit.test=ImportJobFlowIT -Dfailsafe.failIfNoSpecifiedTests=false   # end-to-end job flow only
```

* `TimetableWorkbookParserTest`, `ProgramStructureExtractorTest` — real `seed/` files;
* `AcademicCalendarParserTest` (real `seed/academic-calendar-2026-27.xlsx` and small generated sheets),
  `AcademicCalendarImportHandlerTest` (kinds, scopes, notes, dry-run rows, apply, 409), `NoteTemplateTest`,
  `CalendarOptionsTest`;
* `NormalizationEngineTest`, `TextRulesTest`, `AbsenceTextParserTest`, `ParseAbsenceServiceTest`, `UploadRulesTest`,
  `OrderDocxParserTest` (a DOCX built in the college layout), `CurriculumAndOrderMappingTest`;
* `AiExtractionServiceTest` (fake adapter: models, effort, schema, usage on success/refusal/cut-off/invalid JSON,
  budget, downscaling), `AnthropicAiAdapterTest` (the real SDK against a local HTTP stub: request JSON and
  response handling);
* `ImportJobFlowIT` (PostgreSQL, synchronous executor, stubbed core, fake AI): review → mappings → apply once,
  idempotent re-apply, resume after a failed step, catalog outage, AI order with usage, 429, upload validation,
  list; `ImporterApplicationIT`: security, parse-absence, usage.

Manual run (port 18083, own database): create `importer_<name>` owned by `importer` on the shared Postgres,
run the jar with `SERVER_PORT=18083 DB_NAME=importer_<name>` and a key from `.env`; tokens from `auth`
(`python scripts/rozklad_auth.py token --user admin`, `rozklad-notification` client credentials for SERVICE).

## 10. Known gaps

* Core's import endpoints and `GET /api/internal/catalog/snapshot` were not available on this branch: applying was
  verified only against a stubbed core (ITs). The catalog snapshot `semesters` shape is read leniently
  (`id, name, number?, startsOn, endsOn, active?`).
* Bell profiles are not applied (no endpoint); `departments` are not sent (nothing reliable in the files).
* PDF programs: the text heuristic covers only unambiguous data lines; tested with synthetic logic only (no real
  PDF program in `seed/`).
* No DOCX order sample exists in `seed/`; the DOCX order parser is tested on a generated document.
* Vision accuracy on order photos is good but not perfect (see `importer-ai-samples.md`): the review step is
  enforced (NEEDS_REVIEW until the rows are saved); group codes misread as other existing codes cannot be caught by
  matching, only by the admin's review.
* Single instance assumed: restart recovery marks every `PARSING`/`APPLYING` job of the database.
* A catalog entry created by an apply attempt that core then rejected stays in core when the admin later maps
  that value to an existing record instead (core has no undo for an import; it is marked `needsReview` there).
* `FILESYSTEM` storage: a file written for a rolled-back upload stays in the directory (harmless orphan).
* Academic calendar: core's `calendar-periods` endpoint and `periodKinds` in the snapshot were built in parallel;
  the importer was verified against a stubbed core only. `outOfSemester[].kept` is read leniently (objects with
  `startsOn`/`endsOn` give «обрізано до …», anything else «імпортується лише частина»). `summary.recognized` still
  counts matched catalog entities (groups, rooms), not periods.
