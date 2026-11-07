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
| `PUT /api/import/jobs/{id}/mappings` `{mappings:[{type,raw,targetId?,createNew?,addAlias}]}` | ADMIN | merges decisions, re-matches against a fresh catalog, returns the job |
| `POST /api/import/jobs/{id}/apply` optional body `{semesterId?}` | ADMIN | 202, job in `APPLYING` (or unchanged when already `APPLYING`/`APPLIED`) |
| `POST /api/import/ai/parse-absence` `{text, teacherId?, today}` | ADMIN, SERVICE | rules first, AI only when unsure |
| `GET /api/import/ai/usage` | ADMIN | today's calls/tokens/cost, daily budget, models |

`ImportJobDto` = CONTRACTS shape `{id, kind, fileName, status, createdAt, summary, report, draft, ai?, error?}`
plus additive `fileType, fileSize, updatedAt, createdBy, semesterId, options, mappings, result, canApply`.
`ReportRow` = `{severity, type, raw, matched?, suggestions, location}` plus an additive Ukrainian `message`.
`type` is one of `TEACHER, DISCIPLINE, GROUP, ROOM` (catalog matching, mappable) or `LESSON, CELL, BELLS,
HOURS, ORDER, CURRICULUM, CATALOG, FILE` (parse remarks).

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
  entries / OLE2 `WordDocument` stream (never the extension), validates `options`, checks AI availability and
  the daily budget when the kind needs AI, stores the bytes (DB `bytea` or a volume directory) and publishes
  `ImportJobQueued`; after commit the job runs on a bounded pool (`app.import.workers`, default 2).
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
  step skips finished ones. Calls carry `Idempotency-Key: import-job:<id>:<step>`. A second apply of an applied
  job changes nothing.
* **Restart**: `UPLOADED` jobs are re-queued; `PARSING` → `FAILED` (re-parsing could loop on a poison file and
  re-spend AI money); `APPLYING` → `FAILED` with a "press apply again" message (checkpoints make it safe).

## 3. Kinds

| Kind | Files | Options | Draft | Apply (core) |
|---|---|---|---|---|
| `TIMETABLE_XLSX` | XLSX | `sheet?` (default «Розклад друк»), `versionName?` | `TimetableDraft`: groups, teachers, disciplines, rooms, lessons, bell profiles | 1. `POST /api/admin/import/catalog` (new entities + aliases) 2. `POST /api/admin/import/timetable` → draft version |
| `DISCIPLINE_PROGRAM` | DOCX, DOC, PDF | `groupCodes` (for apply), `hoursPerWeek?` | `ProgramDraft`: name, cycle commission, hours, semester (informative), hours/week, credits, source | 1. catalog (new discipline) 2. `POST /api/admin/import/load` one item per group |
| `CURRICULUM` | PDF scan, JPEG/PNG/WEBP, XLSX | `semesterNumber` (required), `groupCodes` (for apply) | `CurriculumDraft`: rows of that semester | 1. catalog (new disciplines) 2. load items per group × row |
| `ORDER_DOCUMENT` | DOCX; JPEG/PNG/WEBP/GIF/PDF via AI | — | `OrderDraft`: number, kind, date, weekday/parity labels, rows of both sections, notes, signature | `POST /api/admin/import/changes` (text snapshots) |

### TIMETABLE_XLSX parser (DOMAIN §11, tested on the real file)

Structure is found by content, not by fixed rows or colours (the real file differs from DOMAIN's row numbers:
course labels of courses 2–3 are empty merged cells, the curators row of courses 2–4 has the header colour):

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

DOCX via XWPF (cells expanded by `gridSpan`), DOC via HWPF (`poi-scratchpad`), PDF via PDFBox (text only).
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
replacement → FINISHED, else REPLACEMENT). Photos/scans/PDF go to the vision model with the same rules in the
prompt; the answer is validated (unknown row types re-derived with a warning, dates parsed leniently, group codes
canonicalized, model warnings copied into the report).

### CURRICULUM

Vision model on the scan (all pages in one PDF document block) or the text model on an XLSX converted to
tab-separated text; only `options.semesterNumber` is extracted. Checks: hours/week × weeks vs semester hours,
lectures + practice + labs vs classroom hours, sum of hours/week vs the «Всього» row, rows without weekly hours
skipped, no rows → ERROR. See `importer-ai-samples.md` for the live run on `seed/curriculum-ki-2023.pdf`.

### parse-absence

`AbsenceTextParser` (rules): «сьогодні», «завтра», «післязавтра», weekdays («у понеділок»), «4.11», «04.11.2026»,
«4 листопада», ranges «з 4 по 6 листопада», «9-11 листопада», «з 10.11 до 12.11»; a date without a year that passed
more than 14 days ago is next year's. Pairs: «1-2 пари», «з 1 по 3 пару», «2 і 3 пари», «4 пару», ordinals «на
першій парі», «першу і другу пари»; «весь день» → whole day (`pairs` null). Reasons: лікарняний/хворію/приболів
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
  refusals, cut-offs and invalid JSON (those tokens are billed). Images over 5 MB are downscaled to JPEG ≤ 2000 px.
* **Models** `AI_MODEL_TEXT` (default `claude-haiku-5-5`, effort `low`) and `AI_MODEL_VISION` (default
  `claude-sonnet-5-5`, effort `medium`); `AI_MAX_TOKENS` 16000; `AI_TIMEOUT` 180 s; `AI_MAX_RETRIES` 2.
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
| `IMPORT_MAX_FILE_SIZE` | `20MB` | the servlet limit is `UPLOAD_MAX_FILE_SIZE` 21MB so the service answers 413 itself |
| `IMPORT_STORAGE` | `DATABASE` | or `FILESYSTEM` with `IMPORT_STORAGE_DIR` (a volume; atomic writes) |
| `IMPORT_WORKERS`, `IMPORT_QUEUE_CAPACITY` | `2`, `50` | full queue → job FAILED «Черга обробки переповнена» |
| `CORE_URL`, `IMPORTER_CLIENT_SECRET`, `KEYCLOAK_URL` | foundation | catalog snapshot and import calls use the `rozklad-importer` service account |

## 6. Persistence (Flyway `V1__import_jobs_and_ai_usage.sql`)

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
* parse-absence never fails because of AI; 429 for the budget is returned where AI is mandatory (uploads).
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
Header on all: Idempotency-Key: import-job:<jobId>:<step>
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
* `NormalizationEngineTest`, `TextRulesTest`, `AbsenceTextParserTest`, `ParseAbsenceServiceTest`, `UploadRulesTest`,
  `OrderDocxParserTest` (a DOCX built in the college layout), `CurriculumAndOrderMappingTest`;
* `AiExtractionServiceTest` (fake adapter: models, effort, schema, usage on success/refusal/cut-off/invalid JSON,
  budget, downscaling), `AnthropicAiAdapterTest` (the real SDK against a local HTTP stub: request JSON and
  response handling);
* `ImportJobFlowIT` (PostgreSQL, synchronous executor, stubbed core, fake AI): review → mappings → apply once,
  idempotent re-apply, resume after a failed step, catalog outage, AI order with usage, 429, upload validation,
  list; `ImporterApplicationIT`: security, parse-absence, usage.

Manual run (port 18083, own database): create `importer_<name>` owned by `importer` on the shared Postgres,
run the jar with `SERVER_PORT=18083 DB_NAME=importer_<name>` and a key from `.env`; tokens from Keycloak
(`rozklad-dev-cli` admin/admin, `rozklad-notification` client credentials for SERVICE).

## 10. Known gaps

* Core's import endpoints and `GET /api/internal/catalog/snapshot` were not available on this branch: applying was
  verified only against a stubbed core (ITs). The catalog snapshot `semesters` shape is read leniently
  (`id, name, number?, startsOn, endsOn, active?`).
* Bell profiles are not applied (no endpoint); `departments` are not sent (nothing reliable in the files).
* PDF programs: the text heuristic covers only unambiguous data lines; tested with synthetic logic only (no real
  PDF program in `seed/`).
* No DOCX order sample exists in `seed/`; the DOCX order parser is tested on a generated document.
* Vision accuracy on order photos is good but not perfect (see `importer-ai-samples.md`): the review step is
  mandatory; group codes misread as other existing codes cannot be caught by matching.
* Single instance assumed: restart recovery marks every `PARSING`/`APPLYING` job of the database.
* `FILESYSTEM` storage: a file written for a rolled-back upload stays in the directory (harmless orphan).
