# Phase 1 design: contingent and e-journal core

Phase 1 of the diploma (branch `diploma`). Sections 1–10 are the first draft; **section 11 «Decisions after review» is binding and overrides them where they differ.** Anything marked **(assumption)** waits for the college (DOMAIN-style open questions in §9).

## 1. Scope

**In scope for phase 1**
- **Student registry (contingent):** students, group membership history, subgroup assignment per discipline, and a bulk paste from Sheets (TSV) with a dry-run.
- **Gradebooks:** one per semester × group × discipline × subgroup cohort, created from the load and from the lessons.
- **LESSON columns:** derived from SCHEDULED dated lessons and kept in sync by `ScheduleChanged`. A column whose lesson disappears but still holds data becomes an orphan. It is never deleted silently.
- **Custom columns:** CONTROL_PERIOD, MODULE, CONTROL, LAB, COURSE_PROJECT, OTHER.
- **Cells:** a grade, a mark, a comment and a label, with an append-only audit.
- **Dictionaries:** marks and labels, each at college or teacher scope. Every label has a meaning, so the legend is always available.
- **Lesson log:** topic, kind and hours per LESSON column, plus program topics (manual entry, paste, and the importer extension) with suggestions in order.
- **КП columns:** an automatic average with a breakdown of «з чого вийшло», source selection, rounding, and an override with a reason.
- **Grid UI:** Sheets-like keyboard work, range paste, undo.
- **Phone attendance mode:** the current pair, an offline queue in IndexedDB and idempotent sync.
- **Access:** owner teacher, the substitute on their own pair, the curator read-only, and ADMIN.

**Out of scope for phase 1:** finals, debts, attendance reports, student accounts and the `STUDENT` role, notifications, rating, Sheets migration, semester lock, and any RabbitMQ contract changes.

**Vertical slice to build first:** 4КІ1 × one discipline.
1. Admin pastes the students.
2. The gradebook is created and its LESSON columns come from the lessons.
3. The teacher enters «5», «н» and «в» in the grid.
4. An order replaces the teacher on one pair: the column keeps its data and its `lesson_id` is updated.
5. The audit shows who changed what.

## 2. Module placement

Two new Spring Modulith modules go in `core`, not a new service. Reasons:
- The journal needs to read `timetable.Lesson` transactionally. `accounting/internal/AccountingLessons` already does this with JPQL.
- Gradebooks are owned through `load.LoadItem` / `LoadItemTeacher`.
- The in-process `ScheduleChanged` is already published.
- A separate service would have to copy lessons over RabbitMQ.

| Module | Public API (base package) | Internal |
|---|---|---|
| `contingent` | `Student`, `StudentRepository`, `ContingentQueries` (`studentsOf(groupId, from, to)`, `cohort(semesterId, groupId, disciplineId, subgroup)`), `StudentRef`, `StudentStatus` | `internal/*` services, `web/*` controllers, a `SubgroupUsage` implementation |
| `journal` | `JournalQueries` (read-only, for phases 2–3: entries, marks with meaning flags, КП values), `GradebookRef`, `JournalEntriesChanged` (app event) | everything else |

**Dependency direction**
- `contingent` → `catalog`, `shared`.
- `journal` → `contingent`, `timetable`, `calendar`, `catalog`, `load`, `identity` (`CurrentUserService`), `settings` (read), `shared`.
- No coursework module imports `journal` or `contingent`. Add an ArchUnit rule to `ArchitectureRulesTest` for this.

**Facts to know before starting**
- `endedLessons()` is in `accounting/internal/AccountingCalendar` (package-private). Write a small journal copy built on `CalendarQueries.bellSlots(today)` + `Clock`. Do not import the accounting internal.
- Migration ranges are per module, `V1_0xx`–`V1_8xx` (ARCHITECTURE §8), and `out-of-order: ${FLYWAY_OUT_OF_ORDER:true}` is set in `application.yml:33`.
- Use **major version `V2_`** for the diploma: `contingent` `V2_1xx`, `journal` `V2_2xx`, rating `V2_3xx`. This avoids collisions with coursework `V1_8xx` that is still being added. Update ARCHITECTURE §8.

## 3. Data model

All mutable tables carry `id uuid` (generated in Java), `version bigint`, `created_at` and `updated_at`, through `BaseEntity`.

**`V2_100__contingent_student.sql`**
- **`student`** columns:
  - `last_name`, `first_name`, `middle_name`;
  - `record_book_no` (unique, nullable), `email` (unique on lower(), nullable);
  - `funding` REGIONAL_ORDER|CONTRACT|OTHER, which rating needs;
  - `status` STUDYING|ACADEMIC_LEAVE|EXPELLED|GRADUATED;
  - `enrolled_on`, `account_id` (unique, nullable, phase 2), `needs_review`, `note`.
- **`student_membership`** columns:
  - `student_id` FK, `group_id` FK `student_group`;
  - `valid_from date`, `valid_to date` null, with CHECK `valid_to >= valid_from`;
  - `reason` ENROLL|TRANSFER|PROMOTION|RETURN|LEAVE|EXPEL, `order_ref`.
  - Index `(group_id, valid_from)`.
  - Overlaps are checked in code under `SELECT … FOR UPDATE` on the student. This follows the pattern of `LessonRepository.lockGroupDay`; there is no btree_gist extension in the repo.

**`V2_101__contingent_subgroup.sql`**
- **`student_subgroup`** columns: `student_id`, `semester_id`, `group_id`, `discipline_id` (null = default for every split discipline), `subgroup_number ≥ 1`.
- `UNIQUE NULLS NOT DISTINCT (student_id, semester_id, group_id, discipline_id)`.
- It stores numbers, not FKs, like the rest of the repo, and is registered as a `catalog.SubgroupUsage` bean.

**`V2_200__journal_dictionaries.sql`**
- **`journal_mark`** columns:
  - `scope` COLLEGE|TEACHER, `owner_teacher_id`;
  - `code varchar(8)`, `meaning`;
  - `absence bool`, `absence_hours smallint` (null = the pair's hours), `average_mode` IGNORE|ZERO|VALUE, `numeric_value` (when VALUE);
  - `color_token`, `builtin`, `active`, `position`.
  - `UNIQUE NULLS NOT DISTINCT (scope, owner_teacher_id, lower(code))`.
  - Builtin rows use fixed UUIDs `b1000000-…`: «н» (absence, IGNORE), «в» (late, not an absence), «на» (ZERO).
  - **(assumption)** «з», «зк» and «0» are not builtin until the college confirms them (§9).
- **`journal_label`** columns: `scope`, `owner_teacher_id`, `target` STUDENT|CELL|COLUMN, `name`, `meaning` (NOT NULL, this is the legend), `color_token`, `active`, `position`.
  - `color_token` is one of the design palette names, not hex, so it passes `slop-check`.

**`V2_201__journal_gradebook.sql`**
- **`gradebook`** columns:
  - `semester_id`, `group_id`, `discipline_id`, `subgroup_number` (0 = whole group);
  - `teacher_id` (owner snapshot from `load_item_teacher`), `load_item_id` (soft, no FK);
  - `revision bigint` (bumped on every write, used for ETag), `synced_at`.
  - `UNIQUE (semester_id, group_id, discipline_id, subgroup_number)`, index `(teacher_id, semester_id)`.
- **`journal_column`** columns:
  - `gradebook_id` FK, `kind` LESSON|CONTROL_PERIOD|MODULE|CONTROL|LAB|COURSE_PROJECT|OTHER;
  - **slot**: `slot_date`, `slot_pair`;
  - `lesson_id uuid` (FK `lesson` ON DELETE SET NULL, following `V1_502`);
  - snapshot: `lesson_origin`, `lesson_kind`, `teacher_id`, `online`, `change_id`;
  - `state` ACTIVE|ORPHANED|HIDDEN;
  - `title`, `short_title`, `anchor_date` and `position` (where a custom column sits), `label_id`, `counts_in_average bool`, `assessment_period_id`.
  - Partial `UNIQUE (gradebook_id, slot_date, slot_pair) WHERE kind='LESSON'`.
  - CHECK: a LESSON column has a slot; a custom column has a title.
- **`lesson_log`** columns: `column_id` (unique FK), `topic_short varchar(200)`, `topic_full text`, `kind`, `hours smallint`, `program_topic_id` null.

**`V2_202__journal_entry.sql`**
- **`journal_entry`** columns:
  - `column_id`, `student_id`;
  - `grade smallint` (CHECK 0..12, the scale is validated in the service), `mark_id`, `comment varchar(500)`, `label_id`;
  - `override_reason` (КП only), `updated_by`.
  - `UNIQUE (column_id, student_id)`. CHECK: at least one value is set. A grade and a mark may be set together (for example «5» + «в»).
- **`journal_entry_audit`** (append-only, no `version`) columns:
  - `entry_id`, `gradebook_id`, `column_id`, `student_id`;
  - `at`, `actor`, `actor_account_id`, `op` CREATE|UPDATE|DELETE;
  - `before jsonb`, `after jsonb`, `reason`, `source` GRID|PASTE|PHONE|OFFLINE_SYNC, `client_op_id`.
  - Index `(gradebook_id, at desc)`.
  - The service writes it in the same transaction, never through triggers (ARCHITECTURE §8).
- **`journal_client_op`** columns: `op_id uuid PK`, `account_id`, `applied_at`, `result jsonb`. Retention is 30 days, cleaned by an `@Scheduled` job.

**`V2_203__journal_assessment_period.sql`**
- **`assessment_period`** columns: `semester_id`, `number`, `name` («КП1»), `starts_on`, `ends_on`, `course smallint` null (null = all courses).
- **(assumption)** The journal owns this table, admin-managed, instead of a new `PeriodEffect`. Bounds are copied from the academic calendar by hand until §9 Q2 is answered.
- **`control_period_config`** columns: `column_id` PK, `source_mode` PERIOD_AUTO|EXPLICIT, `include_kinds text[]`, `rounding` HALF_UP|FLOOR|CEIL, `decimals` 0|1, `min_grades`.
- **`control_period_source`** columns: `column_id`, `source_column_id`, with PK on both.

**`V2_204__journal_program.sql`**
- **`discipline_program`** columns: `discipline_id`, `semester_id` null, `source_file`, `imported_at`, `active`.
- **`program_topic`** columns: `program_id`, `position`, `section`, `title`, `lesson_kind`, `hours`, with `UNIQUE (program_id, position)`.

**`V2_205__journal_student_label.sql`**
- **`journal_student_label`** columns: `student_id`, `label_id`, `gradebook_id` null (null = every gradebook of the group), `valid_from`, `valid_to`.

## 4. Column derivation

**Cohort rule (same as `HoursLedger`)**
- A discipline is split when some `load_item` with that semester, group and discipline has `split_subgroups`.
- If split, there are gradebooks k = 1..n, where n is the number of the group's `subgroup` rows. Otherwise there is one gradebook with k = 0.
- Gradebook k receives the SCHEDULED lessons with `subgroup_number IN (0, k)`.

**`ColumnSync.sync(groupId, semesterId, dateRange)`**
- It is idempotent and pure-diff. There are three queries per group:
  1. the lessons, in the projection `AccountingLessons` uses, extended with `origin`, `changeId`, `teacherId`, `lessonKind` and `online`;
  2. the gradebooks;
  3. the LESSON columns joined with their entry count.
- It diffs in memory by slot key, separately for each gradebook.

| Situation | Result |
|---|---|
| A new slot | Insert an ACTIVE column. Future lessons are included and shown as «заплановані». |
| The slot exists with a different `lesson_id` (REPLACE_TEACHER, ROOM_CHANGE, republication, MERGE of the same discipline) | Update `lesson_id` and the snapshot. The data stays. |
| The slot is gone and the column has no entries and no log | Delete it. |
| The slot is gone and the column has entries or a log (CANCEL, SWAP, REPLACE_DISCIPLINE, a MERGE into the partner's discipline) | Set ORPHANED and keep the data. In the UI the column is read-only and offers «Перенести на іншу пару» or «Видалити». |
| The slot comes back | ORPHANED becomes ACTIVE again. |
| ADDITIONAL / BLOCK over pairs 1–4 | 4 columns. **(assumption)** The UI groups adjacent slots of one `change_id` under one header. |
| ONLINE flip | Only the snapshot `online` changes. |

**Triggers**
- `@ApplicationModuleListener on(ScheduleChanged e)` syncs `e.groupIds() × [min(dates), max(dates)]`, past dates included, because orders can be back-filled.
- `POST /api/admin/journal/semesters/{id}/gradebooks` creates the gradebooks from load items and from lessons that have no load item, then runs a full sync. It is idempotent.
- The admin endpoint `…/gradebooks/{id}/sync` re-runs the sync for one gradebook.
- **(inference)** Modulith delivers the externalized event to in-process listeners too. Verify this with a `Scenario` test.

**Writes are allowed** on LESSON columns from the pair's start time, so the current pair can be marked. Custom columns can be written at any time.

**Consistency check:** the number of ACTIVE ended LESSON columns × `hoursPerPair` equals `DisciplineProgress.fact` for the cohort. Assert this in an IT.

**SQL budgets** (Hibernate `Statistics`, as in `OptionsGatheringIT`)
- Gradebook load, 25 students × 80 columns: ≤ 8 statements.
- A batch write: ≤ 8 statements + inserts (JDBC-batched).
- A sync: ≤ 3 + writes per group.

## 5. REST API

Teacher endpoints sit under `/api/me/journal/**`, which `SecurityConfig` already allows for TEACHER and ADMIN. Admin endpoints sit under `/api/admin/students/**` and `/api/admin/journal/**`. Ownership is checked in `JournalAccess` inside the services, not by role alone.

| Endpoint | Notes |
|---|---|
| `GET /me/journal/gradebooks?semesterId` | My gradebooks plus the ones I curate (`role: OWNER/CURATOR/SUBSTITUTE`). |
| `GET /me/journal/gradebooks/{id}` | `GradebookDto{gradebook, students[], columns[], entries[] (sparse), log[], marks[], labels[], studentLabels[], revision}`. `ETag: "r<revision>"`; `If-None-Match` returns 304. |
| `POST /me/journal/gradebooks/{id}/entries/batch` | `{atomic, source, ops:[{opId, columnId, studentId, baseVersion?, grade?, markCode?, comment?, labelId?, overrideReason?, clear?}]}` with at most 500 ops. Response: `{revision, results:[{opId, status: APPLIED|DUPLICATE|CONFLICT|REJECTED, entry?, problem?}]}`. A paste sends `atomic=true`: any rejection rolls back everything and returns 422 with `results`. The offline queue sends `atomic=false`. |
| `POST/PUT/DELETE /me/journal/gradebooks/{id}/columns[/{cid}]` | Custom columns with `version`. Deleting a column that has entries returns 409 `COLUMN_HAS_ENTRIES` unless `?withEntries=true`. |
| `PUT /me/journal/columns/{cid}/log` | `{topicShort, topicFull, kind, hours, programTopicId, version}` |
| `GET /me/journal/gradebooks/{id}/topic-suggestions`, `POST …/accept {columnIds}` | Walks the LESSON columns in order and takes the next unused program topic of the matching kind. A 4-hour topic covers 2 pairs. |
| `GET/PUT /me/journal/columns/{cid}/computation` | КП config plus a per-student breakdown `{value, used:[{columnId, grade}], skipped:[…]}`. |
| `GET /me/journal/now` | The caller's current or next pair: the gradebook, the column, the students and their entries. |
| `GET /me/journal/gradebooks/{id}/history?columnId&studentId&page` | Audit, paged. |
| `GET/POST/PUT/DELETE /me/journal/marks`, `/me/journal/labels`, `/me/journal/student-labels` | Teacher scope. College scope is under `/admin/journal/marks` and `/admin/journal/labels`. |
| `/admin/students` | CRUD and `POST /admin/students/bulk?dryRun` (TSV) |
| `/admin/groups/{id}/students`, `/admin/students/{id}/memberships` | Membership history |
| `PUT /admin/groups/{g}/subgroups?semesterId` | Subgroup assignment. The gradebook owner may also call it for their own discipline. |
| `/admin/journal/assessment-periods` | КП bounds |

**Who may do what**
- The OWNER (`gradebook.teacher_id` = my teacher id) can do everything in their gradebooks.
- A SUBSTITUTE (`column.teacher_id` = me) can write cells on that LESSON column only.
- The CURATOR (`student_group.curator_teacher_id`) reads only. Curator is derived, not a stored role.
- ADMIN reads everything. ADMIN writes only with a `reason`, which is audited **(assumption)**.

**Errors** (ProblemDetail, `BusinessRuleException` codes, with new type slugs added to `ProblemTypes`)
- 422: `PAIR_NOT_STARTED`, `COLUMN_ORPHANED`, `STUDENT_NOT_IN_GRADEBOOK` (outside the membership dates or another subgroup), `MARK_UNKNOWN`, `OVERRIDE_REASON_REQUIRED`, `GRADE_OUT_OF_SCALE`.
- 403: `JOURNAL_NOT_OWNER`.
- 409: `stale-data` for a version mismatch on columns and dictionaries.
- `CONFLICT` inside a batch result means `baseVersion` does not match the current entry. The client shows both values.

## 6. Events

- **Consumed:** `ScheduleChanged` (in-process). No contract change.
- **Published, in-process:** `JournalEntriesChanged{gradebookId, revision, columnIds}`. It feeds an SSE `journal.changed` through `SseAfterCommit.sendToRole`. The payload carries no student data; clients refetch through `If-None-Match`.
- **No RabbitMQ contracts in phase 1.** Phase 2 adds `journal.*` events to `libs/contracts`, versioned and recorded in `CHANGELOG-CONTRACTS.md`.

## 7. Web

**Routes in `ROUTES`**
- `/me/journal`: the list.
- `/me/journal/:gradebookId`: the grid. The URL keeps `?range=kp1&col&student`.
- `/me/journal/now`: the phone mode.
- `/me/journal/settings`: marks and labels.
- `/admin/students`, `/admin/journal`.

**Grid** (`components/journal/grid/`)
- ARIA `grid` with sticky student and header rows and a roving cell (pattern from `PreferenceGrid` + `board/keyboard.ts`).
- Columns are virtualized with `@tanstack/react-virtual`, already a dependency, when there are more than 60 of them.
- Keys:
  - typing a digit or a letter starts editing with that character;
  - Enter commits and moves down; Tab moves right; Esc cancels; F2 edits; Delete clears;
  - Shift+arrows select a range; Ctrl+C/V copy and paste TSV, and a paste becomes one atomic batch;
  - Ctrl+Z goes through `lib/undo` `trackSavedRecord`, keeping the version.
- Mark autocomplete comes from the dictionary. Hovering a header shows the lesson origin through `HoverDetails`.
- Orphan columns are hatched. A legend lists every label used.
- Below 768 px there is a per-column list view (DESIGN §13 forbids horizontal scroll).

**Panels and dialogs:** a lesson log side panel with suggestions (accept all / per row), the КП dialog (sources, rounding, breakdown, override with a reason), and the audit drawer.

**Phone mode:** a `Drawer` list with large tap targets. A tap cycles present → «н» → «в»; a long press opens all marks. Each tap queues an op. A badge shows «N не синхронізовано».

**Offline queue** (`lib/offline/`)
- A small IndexedDB wrapper, with no new dependency.
- Each op: `opId = crypto.randomUUID()`, payload, `baseVersion`, attempts.
- It flushes from the page on `online`, on `visibilitychange`, on a 30 s interval and at start, as `atomic=false` batches.
- There is no service-worker Background Sync, so tokens stay out of the service worker (rule in `public-api-cache.ts`).
- The snapshot of today's pairs is stored in IndexedDB for 24 h and wiped on logout **(assumption, §9)**.

**Data layer:** `api/journal.ts` and `api/contingent.ts`; the `qk.journal` and `qk.contingent` branches; DTOs in `types.ts`.

**Mocks:** `mocks/handlers/journal.ts` and `contingent.ts`. Fixture students for 4КІ1 are **generated fake names**, never the real list.

**Accessibility:** cell `aria-label` = «Прізвище І., 12.11 пара 3: 5, в»; `aria-live` announces sync results. Status is shown by shape and word, not by colour alone.

## 8. Tests

**Unit**
- `ColumnDiff` for every change kind in §4, idempotency included.
- `CohortRule`.
- `ControlPeriodCalculator`: rounding, ZERO and IGNORE marks, empty periods, overrides.
- `TopicSuggester`.
- `JournalAccess` matrix.
- Membership overlap.
- Batch op validation.

**Slice:** `@WebMvcTest` per controller, covering the ProblemDetail codes, 403, ETag/304 and validation messages in Ukrainian.

**IT** (Testcontainers)
- `JournalSyncIT`: publish a version → columns; publish an order with REPLACE_TEACHER / CANCEL / MERGE → id updated / orphaned / moved; a back-dated order.
- `GradebookReadIT`, with the budgets from §4.
- `EntryBatchIT`: an atomic rollback; a duplicate `opId` returns DUPLICATE; a version conflict; an audit row per op.
- A fact-parity check against `AccountingQueries`.
- `ModularityTests` and an ArchUnit rule that nothing depends on `journal`.

**Web unit:** grid keyboard model, TSV parse, offline queue (fake IndexedDB), КП dialog, and the mock handler tests.

**E2E** (`desktop` and `mobile`)
- Enter grades → reload → the values are still there.
- Paste a 3×4 range, then Ctrl+Z.
- Phone mode offline (`context.setOffline`): mark 3 students → go online → synced, with no duplicates.

**Slice acceptance:** the five steps of §1 pass end to end on the dev stand.

## 9. Open questions for the college (defaults assumed meanwhile)

1. What do «з», «зк», «0» and «Н» vs «н» mean? *Assumed:* «н» = «Н» (absence of the pair's hours); the others are teacher-defined.
2. Where do КП bounds come from, and do they differ by course? *Assumed:* admin-entered `assessment_period`, college-wide.
3. Rounding of the КП. *Assumed:* HALF_UP to an integer, and a mark with ZERO counts as 0.
4. Does a substitute teacher grade in the owner's gradebook? *Assumed:* yes, on their own pair only.
5. Should grading future pairs be allowed? *Assumed:* no, writes are open from the pair's start.
6. Can personal data (record book number, e-mail) be stored, and can student names be cached on teachers' phones? *Assumed:* yes for both, with a 24 h TTL and a wipe on logout.
7. Does a BLOCK spanning 4 pairs count as 4 columns? *Assumed:* yes, shown grouped.
8. Does the whole-group lecture of a split discipline appear in each subgroup gradebook? *Assumed:* yes, following the `HoursLedger` cohort rule.

## 10. Implementation order

**Backend** (each step: green `mvn -pl services/core -am verify`, docs updated, a `CHANGELOG-DIPLOMA.md` entry)

| Step | Work | Done when |
|---|---|---|
| B1 | `contingent` module, `V2_100`/`V2_101`, entities, `ContingentQueries`, `SubgroupUsage` | Unit and IT pass for overlap and cohort; ModularityTests pass. |
| B2 | Admin student API and bulk TSV with dry-run | Slice tests and errors pass. |
| B3 | `journal` skeleton, `V2_200`/`V2_201`, dictionaries with seeds, an ArchUnit rule | CRUD with version checks and 409. |
| B4 | Gradebook creation from the load and `ColumnSync` with the listener | `JournalSyncIT` covers every change kind; fact parity holds. |
| B5 | `V2_202`, entries batch with idempotency, audit | `EntryBatchIT` and budgets pass. |
| B6 | Gradebook read with ETag, `JournalAccess` | Access matrix tested; read ≤ 8 statements. |
| B7 | Custom columns, `V2_203`, КП computation and override | Calculator unit tests; breakdown API works. |
| B8 | Lesson log, `V2_204`/`V2_205`, topic suggestions; importer keeps `TopicPlan` rows and applies them through a new core endpoint | Suggestions follow the program order in an IT. |
| B9 | `/me/journal/now`, history API, SSE `journal.changed` | IT passes; SSE carries no personal data. |

**Web** (each step: `npm run lint && npm test && npm run build`, e2e where a flow is touched)

| Step | Work | Done when |
|---|---|---|
| W1 | Types, `qk` branches, API hooks, MSW handlers and fake fixtures | Handler tests pass. |
| W2 | `/admin/students` (list, bulk paste, memberships, subgroups) | Unit tests and the e2e paste pass. |
| W3 | Read-only grid with sticky panes, legend, orphan state | Renders 25×80 within budget; axe is clean. |
| W4 | Editing: keyboard model, mark autocomplete, undo | Keyboard e2e passes. |
| W5 | Range selection and TSV paste as an atomic batch | e2e paste plus undo passes. |
| W6 | Marks and labels settings, student labels | Unit tests pass. |
| W7 | Lesson log panel and suggestions | Unit tests pass. |
| W8 | КП dialog with the breakdown and the override | Unit tests pass. |
| W9 | Phone mode and the offline queue | The offline e2e on mobile passes. |
| W10 | Audit drawer and the SSE refresh | Unit tests pass; the e2e slice passes. |

## 11. Decisions after review (binding)

An adversarial review checked the draft against the code. These decisions replace the draft where they differ.

### 11.1 One gradebook per group × discipline × semester

- `gradebook` is unique on `(semester_id, group_id, discipline_id)`. There is no `subgroup_number` on the gradebook.
- Every `journal_column` carries `subgroup_number` (0 = whole group). A cell of a student who is not in that
  subgroup on that date is «не для цієї підгрупи»: shown greyed, not writable.
- The web offers «Уся група / Підгрупа 1 / Підгрупа 2» as a filtered view, which matches the Sheets habit of a
  separate sheet per subgroup without duplicating whole-group lectures.
- `student_subgroup` gets `valid_from` / `valid_to` (a student may move mid-semester); history is kept.
- Cohorts for column sync = subgroup numbers seen in the lessons ∪ the load split; no lesson is ever dropped.

### 11.2 Ownership is resolved at request time

- No owner snapshot on `gradebook`. `JournalAccess` resolves roles per request:
  - **OWNER**: the teacher is in `load_item_teacher` for any load item of (semester, group, discipline), any lesson
    kind, any subgroup; or teaches an ACTIVE LESSON column of this gradebook as the scheduled (not substitute) teacher.
  - **SUBSTITUTE**: `journal_column.teacher_id` = me and the column came from a change. Sees only the student list and
    its own columns; writes only them; access ends 14 days after the pair (**assumption**).
  - **CURATOR**: `student_group.curator_teacher_id` = me. Read-only.
  - **ADMIN**: reads all; writes need a `reason`, audited.
- The access lookup is folded into the gradebook read query where possible; budget for the read is ≤ 12 statements.

### 11.3 Column sync

- `ColumnSync.sync` locks the group's gradebooks `SELECT … FOR UPDATE` in id order before diffing.
- A changed lesson is followed through `lesson.change_id` → `change.original_lesson_id` / `swap_lesson_id`: when the
  mapping old slot → new slot is 1:1 within the same gradebook (SWAP, moved pair), the column **moves** with its data
  (audited as MOVE) instead of becoming ORPHANED.
- Only entries make a column «has data». A planned topic on a future column does not (see 11.5).
- MERGE_SUBGROUPS: the merged lesson is a whole-group column taught by the partner, who is SUBSTITUTE for it.

### 11.4 Revision, ETag, events

- `gradebook.revision` is bumped with `UPDATE … SET revision = revision + 1 RETURNING`, not with `@Version`, so two
  teachers writing different cells never conflict on the gradebook row.
- The ETag is a hash of (revision, latest change of memberships, subgroups, dictionaries and student labels touching
  the gradebook, caller role). `Cache-Control: private`, `Vary: Authorization`; the body also carries `revision`.
- SSE `journal.changed` goes with `sendToUser` to the owners and the curator only, never to the whole TEACHER role.

### 11.5 Topics are a plan, not data

- The lesson log of a held pair is data. Suggestions for future pairs are a **topic plan per gradebook** (ordered list),
  bound to columns only when the pair is held or the teacher confirms it. A cancelled pair re-flows the plan.
- Phase 1 fills the plan by manual entry and paste (a column of topics from Word/Sheets). Importing topics from the
  discipline program document is phase 4: `importer/program/TopicPlan` sums hours today and extracts no titles.
- A plan item has hours per kind (lecture / practical / lab), because thematic plans list several kinds per row.

### 11.6 Marks

- Codes are unique case-insensitively within the visible set: a teacher cannot define a code that a college mark
  already uses. «Н» and «н» are the same mark (**assumption** until the college answers).
- Builtin college marks: «н» (absence, counts the pair's hours, ignored in averages), «в» (late, not an absence),
  «на» (not certified, counts as 0 in averages). Others are teacher-defined.

### 11.7 Control periods (КП) come from the calendar

- No `assessment_period` table. A КП is a calendar period of a new builtin period kind «Контрольний період» with
  effect `INFO` (lessons untouched), scoped by courses or groups through the existing calendar UI and import.
- INFO periods of this kind must not appear in public day notes or orders; verify `noteTemplate` / `periodsOn` and
  hide the kind there.
- The КП column links to the period. Sources: by default every numeric grade of the gradebook inside the period;
  the teacher may tick columns in or out; rounding HALF_UP to an integer by default; override with a reason.

### 11.8 Offline queue

- Ops are coalesced per (column, student) in IndexedDB: the first `baseVersion`, the last value. Ops expire after 7
  days; the snapshot after 24 h; both are wiped on logout.
- `journal_client_op` is keyed by `(account_id, op_id)`.
- Phone mode shows a list of unresolved conflicts with both values.
- `/me/journal/now` returns a list of (gradebook, column) pairs: streams and whole-group lectures of split
  disciplines give one merged roster.

### 11.9 Audit covers everything that changes a grade

Cell writes, column delete with entries, column move, КП config / sources / override, lesson log and student labels
all go through one audit writer (`op`: CREATE, UPDATE, DELETE, MOVE, DELETE_COLUMN, CONFIG). No FK cascade from the
audit to entries. The audit drawer can restore a previous value (a new audited write).

### 11.10 Students

- `student` has no `status` column: status is derived from the membership history (LEAVE / EXPEL close it).
- Funding is dated: `student_funding(student_id, funding, valid_from)`, because a transfer changes the rating base.
- Real student lists never go into the repository, mocks or seeds; fixtures use generated names.

### 11.11 Sheets parity in phase 1

- Fill down / fill the selection, range paste, undo of a whole paste as one step.
- Export of a gradebook to CSV and a print view (A4 landscape). XLSX export needs a new production dependency and
  waits for the owner's approval.

### 11.12 Housekeeping

- Migrations: `contingent` `V2_1xx`, `journal` `V2_2xx` (ARCHITECTURE §8 updated).
- Add `contingent` and `journal` to `ArchitectureRulesTest` module lists; ArchUnit rule: no coursework module depends
  on them.
- Index `journal_column(lesson_id)`.
- Web code uses the word `gradebook` (`qk.gradebook`, `components/gradebook/`), because `journal` already names the
  undo «Журнал дій».
- Teacher endpoints for subgroup assignment live under `/api/me/journal/gradebooks/{id}/subgroups`.

### 11.13 Order of work

- **Phase 1a**: contingent; gradebooks and column sync; marks and labels; entries batch with idempotency and audit;
  gradebook read with access; CSV export and print. Web: data layer and mocks, admin students, grid (read, edit,
  range paste, fill, undo), marks and labels settings, legend, audit drawer.
- **Phase 1b**: КП periods and columns; topic plan and lesson log; `/me/journal/now`, phone mode and the offline
  queue; SSE refresh.
