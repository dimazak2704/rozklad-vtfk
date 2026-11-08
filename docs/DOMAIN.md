# Domain — rules, schema, source formats

Language: identifiers English, all user-facing text Ukrainian. Time zone `Europe/Kyiv`.
Evidence: real college files in `seed/` (Excel schedule 2025/26, order photos №35/№96/№105,
discipline programs) and the college site (bells, curriculum).

## 1. Glossary

| UA | Code | Meaning |
|---|---|---|
| група | `StudentGroup` | e.g. `4КІ1` = course 4 + specialty letters `КІ` + number 1. Has `shift`, `size`, curator, home room |
| підгрупа | `Subgroup` | split of a group for labs / foreign language. `subgroupNumber` 1..n; **0 = whole group** |
| потік | `Stream` | one lesson for several groups (optional, same teacher/room/slot) |
| викладач | `Teacher` | short form `Кренцін М.Д.`, display `Кренцін`; cycle commission; STAFF or PART_TIME |
| дисципліна | `Discipline` | full name + short name (`СТП`) + aliases |
| аудиторія | `Room` | number/name, type, capacity, `maxParallel` (sports hall «с.з.» hosts several groups at once) |
| навантаження | `LoadItem` | group × discipline × semester: hours/week, planned hours, teacher(s), subgroup split |
| пара | pair | numbered slot of a day (1..8); times come from the active bell profile |
| чисельник / знаменник | parity `NUMERATOR`/`DENOMINATOR` | alternating weeks; lessons can be `BOTH`, NUM-only or DEN-only |
| базовий розклад | `TimetableVersion` + `TemplateLesson` | weekly template (weekday × pair × parity) |
| заняття на дату | `Lesson` | materialized occurrence for a concrete date — the single source for schedule, changes, accounting |
| відсутність | `Absence` | teacher absence (dates, pairs, reason category) |
| заміна / зміна | `Change` | a modification of the schedule on a date (replacement, cancellation, extra pair, …) |
| розпорядження | `OrderDocument` | official daily order listing changes for a date, numbered per semester |
| доповнення | amendment | order published after the main order of the same date (only new changes) |
| вичитано | finished | discipline's planned hours are reached |
| зміна (shift) | `shift` 1/2 | first shift pairs 1–4; second shift pairs 5–8 Mon–Thu and 4–6 Fri |

## 2. Calendar & parity

- Study days Mon–Fri. Week = Monday-based. Week 1 = week containing `semester.startsOn`.
- `parity(week n)` = `firstWeekParity` (default NUMERATOR) for odd n, the other for even n;
  `week_parity_override` wins. **Verified**: semester start 01.09.2026 (Tue) ⇒ week of 19.10.2026 is week 8 ⇒ ЗНАМЕННИК (matches order №35).
- A date has lessons if inside the semester and not inside a global `HOLIDAY`/`NO_CLASSES` period.
  Group-scoped `PRACTICE` periods remove that group's lessons. `STUDY_MODE` periods (ONLINE/MIXED) keep
  lessons but mark them online and produce notes for orders (e.g. «Групи 4КІ з 01.09.26р. по 31.10.26 навчаються – дистанційно.»).
- Bell profiles (from Excel sheets «дзвінки (2 зм)», «дзвінки» and the college site). Active default = **«2 зміни, 60 хв»**:
  - Mon–Thu: 1 08:30–09:30, 2 09:40–10:40, 3 11:00–12:00, 4 12:10–13:10, 5 13:30–14:30, 6 14:40–15:40, 7 16:00–17:00, 8 17:10–18:10
  - Fri: 1 08:30–09:30, 2 09:40–10:40, 3 11:00–12:00, 4 12:20–13:20, 5 13:30–14:30, 6 14:50–15:50
  - «2 зміни, 50 хв» Mon–Thu: 08:30-09:20, 09:30-10:20, 10:40-11:30, 11:40-12:30, 12:50-13:40, 13:50-14:40, 15:00-15:50, 16:00-16:50; Fri: 08:30-09:20, 09:30-10:20, 10:40-11:30, 11:50-12:40, 12:50-13:40, 14:00-14:50
  - «1 зміна, 80 хв»: 08:30-09:50, 10:00-11:20, 12:00-13:20, 13:30-14:50
- Shift windows (table `shift_window`): shift 1 → pairs 1–4 Mon–Fri; shift 2 → pairs 5–8 Mon–Thu, 4–6 Fri.
  In the real schedule the 2nd course is shift 2, other courses shift 1.
- Hours per pair: setting `accounting.hoursPerPair` (default 2 academic hours, even though pairs are 60 min now).

## 3. Core schema (PostgreSQL, Flyway, module ranges in ARCHITECTURE §8)

All tables: `id uuid pk`; mutable aggregates also `created_at`, `updated_at timestamptz`, `version bigint`.

**catalog**
- `department(name, short_name)`
- `specialty(code '123', letters 'КІ', name, department_id)`
- `cycle_commission(name)`
- `teacher(last_name, first_name, middle_name, short_name, display_name, cycle_commission_id, employment 'STAFF'|'PART_TIME', email, active, keycloak_user_id uuid unique null, needs_review bool)`
- `teacher_alias(teacher_id, alias, normalized unique)`
- `discipline(name, short_name, color, active, needs_review bool)`; `discipline_alias(discipline_id, alias, normalized unique)`
- `teacher_discipline(teacher_id, discipline_id)` — competence used by candidate ranking
- `building(name)`; `room(building_id, name, type, capacity, max_parallel int default 1, active)`;
  room types: `CLASSROOM, COMPUTER_LAB, LAB, GYM, SPORTS_GROUND, OTHER`
- `student_group(code unique, course, specialty_id, number, size, shift, curator_teacher_id, home_room_id, active)`
- `subgroup(group_id, number, name, size)` — created when a group is split; number 0 is implicit (not stored)

**calendar**
- `academic_year(name, starts_on, ends_on)`
- `semester(academic_year_id, number, name, starts_on, ends_on, first_week_parity, weeks_count, bell_profile_id, active)`
- `week_parity_override(semester_id, week_start, parity)`
- `bell_profile(name, active)`; `bell_slot(profile_id, weekday 1..5, pair_number, starts_at time, ends_at time)`
- `shift_window(shift, weekday, first_pair, last_pair)`
- `calendar_period(semester_id, kind HOLIDAY|NO_CLASSES|PRACTICE|STUDY_MODE|EXAM_SESSION, title, starts_on, ends_on, study_mode OFFLINE|ONLINE|MIXED null, order_note text null, scope ALL|COURSES|GROUPS, courses smallint[] null)` + `calendar_period_group(period_id, group_id)`

**load**
- `load_item(semester_id, group_id, discipline_id, lesson_kind LECTURE|PRACTICE|LAB|SEMINAR|MIXED, hours_per_week numeric(4,1), planned_hours int, split_subgroups bool, required_room_type null, preferred_room_id null, control_form EXAM|CREDIT|NONE, stream_id null, source MANUAL|CURRICULUM|PROGRAM|TIMETABLE_DERIVED, note)`
- `load_item_teacher(load_item_id, subgroup_number, teacher_id)` — subgroup 0 when not split

**timetable**
- `timetable_version(semester_id, name, status DRAFT|PUBLISHED|ARCHIVED, source MANUAL|IMPORT|GENERATED|COPY, effective_from, published_at, published_by, generation_job_id, metrics jsonb, notes)`
- `template_lesson(version_id, weekday, pair_number, parity BOTH|NUMERATOR|DENOMINATOR, group_id, subgroup_number, discipline_id, teacher_id null, room_id null, load_item_id null, lesson_kind, stream_id null, pinned bool, online bool)`
- `lesson(semester_id, version_id null, template_lesson_id null, date, pair_number, group_id, subgroup_number, discipline_id, teacher_id null, room_id null, room_exclusive bool, status SCHEDULED|CANCELLED, origin TEMPLATE|CHANGE|ADDITIONAL|BLOCK, change_id null, stream_id null, lesson_kind, online bool)`
  - partial unique indexes (status='SCHEDULED'): `(teacher_id,date,pair_number)` where teacher_id not null and stream_id null;
    `(room_id,date,pair_number)` where room_exclusive and room_id not null and stream_id null;
    `(group_id,subgroup_number,date,pair_number)`.
  - cohort overlap (whole group vs subgroup) is checked in code under a pessimistic lock on the group's lessons of that date.
- `generation_job(semester_id, status QUEUED|RUNNING|SOLVED|FAILED|CANCELLED, params jsonb, started_at, finished_at, best_score, progress jsonb, result_version_id, error, requested_by)`

**substitution**
- `absence(teacher_id, date_from, date_to, pairs smallint[] null (null=all day), reason_category SICK_LEAVE|VACATION|BUSINESS_TRIP|PERSONAL|OTHER, comment, source WEB|BOT|ADMIN, status ACTIVE|CANCELLED, created_by)`
- `change(date, pair_from, pair_to, group_id, subgroup_number, kind REPLACE_TEACHER|REPLACE_DISCIPLINE|SWAP|CANCEL|ADDITIONAL|ROOM_CHANGE|BLOCK|DISCIPLINE_FINISHED, original_lesson_id null, orig_discipline_id, orig_teacher_id, orig_room_id, new_discipline_id, new_teacher_id, new_room_id, reason_code, reason_text, absence_id null, status DRAFT|PENDING_CONFIRMATION|CONFIRMED|DECLINED|CANCELLED|APPLIED, approval_mode AUTO|CONFIRM|NOTIFY, confirm_deadline, responded_at, response_comment, teacher_answer CONFIRM|DECLINE null, order_id null, created_by)`
- `change_history(change_id, at, actor, from_status, to_status, comment)`
- `teacher_preference(teacher_id, semester_id, weekday, pair_number, kind UNWANTED|UNAVAILABLE, note)`

**order** (`order` is reserved → `order_document`)
- `order_document(semester_id, number int null, date, kind ORDER|AMENDMENT, parent_id null, status DRAFT|PUBLISHED, title, weekday_label, parity_label, signatory_position, signatory_name, notes jsonb, published_at, published_by)`;
  unique `(semester_id, number)` where number not null
- `order_item(order_id, section CHANGES|ROOM_CHANGES, position, group_code, pair_label, from_discipline, from_teacher, reason_text, to_discipline, to_teacher, room_text, row_type REPLACEMENT|CANCELLATION|ADDITIONAL|BLOCK|FINISHED|AS_SCHEDULED|ROOM_CHANGE, change_id null)` — denormalized text snapshot (document of record)

**identity/settings/shared**
- `invite(teacher_id, token_hash, expires_at, used_at, created_by)`
- `setting(key pk, value jsonb, updated_at, updated_by)`
- `processed_event(event_id pk, processed_at)`; Spring Modulith `event_publication` table (DDL from Modulith docs for PostgreSQL)

## 4. Load → weekly units

`pairsPerWeek = hoursPerWeek / hoursPerPair`; `whole = floor(p)`, remainder ≥ 0.25 and < 0.75 → one HALF
unit (NUM or DEN, chosen by the solver), ≥ 0.75 → one more whole. Examples: 3 h → 1 + ½; 4 h → 2; 2 h → 1; 1 h → ½.
Split subgroups ⇒ units per subgroup with that subgroup's teacher. Planned hours default = hoursPerWeek × weeks.
Generator does **not** chase exact semester totals — the college stops with «Вичит.» when hours are done;
accounting forecasts that (§8).
Real example (curriculum КІ 2023 ред. 2025, semester 7, 22 weeks, 31 h/week): Комп. мережі 4 h,
Економіка та управління ІТ-проєктами 3 h, НДЕКСМ 2 h (24 lect + 20 lab = 44 h; the discipline program says 40),
Системне програмування 2, Охорона праці та ЦЗ 2, Соціологія 2, Іноземна мова 2, Укр. мова (проф.) 2, Фізвиховання 2.

## 5. Conflicts (template and dated)

Two lessons on the same weekday/date and pair conflict when parities overlap (BOTH overlaps all; NUM≠DEN) and:
- same teacher (unless same `stream_id`);
- same group and cohorts overlap (subgroup 0 overlaps all subgroups of the group; k overlaps 0 and k);
- same room with `max_parallel = 1` (unless same stream); rooms with `max_parallel = n` allow n lessons.

An **online** lesson (template flag, or a dated lesson on a STUDY_MODE ONLINE day) keeps its room for
reference but does not occupy it: room occupancy, capacity and room type are not checked for it, and its
dated lesson is not room-exclusive (order №35 gives room 403 to 1ОП2 while 3ОБ studies online there).
Hard violations (block publication, 409 with `conflicts[]`): the above, capacity < group size (whole-group
lesson), required room type mismatch, lesson outside the group's shift window, teacher UNAVAILABLE slot.
Soft warnings (shown, not blocking): cohort/teacher **windows** (gap inside a day for a given parity),
**«рваний день»** (for a group-day the NUM and DEN lesson counts or first pair differ), > 4 pairs/day,
same discipline ≥ 3 times/day, UNWANTED preference.
Key insight for «рівний день»: two HALF units of different disciplines in the same slot (A NUM, B DEN)
keep both weeks equal. The real 4КІ1 schedule has a NUM-only pair on Wednesday and no NUM first pair on
Thursday — two halves in different slots; putting them into one slot removes both broken days.

## 6. Publication & materialization

Publishing version V (effective from F): validate hard conflicts → archive the previously published
version of the semester → for every study date d in [F, semester end]: parity(d), take template lessons
with weekday(d) and parity ∈ {BOTH, parity(d)}, skip groups in PRACTICE on d, set `online` from STUDY_MODE,
insert `lesson` rows (origin TEMPLATE). Future TEMPLATE lessons of the old version (date ≥ F) not referenced
by a live change are deleted first (a DECLINED or CANCELLED change pins nothing); lessons created/modified by
applied changes are kept and reported if they now conflict; old-version lessons kept for an open decision are
reported as `pendingDecisions` and replaced by the new version once that decision is cancelled or declined.
Emits `timetable.version.published` and `schedule.changed`.

Dated lessons keep following the calendar after publication: a calendar edit (holiday, practice, study mode,
parity override, semester dates) re-materializes the affected dates and groups from today on with the version
that governs each date (the one published last among those effective by then); lessons that still match keep
their rows (an ONLINE period only flips `online` and frees the room), lessons of live changes keep their rows but
follow the study mode of their day too (online when the template lesson they stand for is online or the group studies
online that day), past days stay as history. Within one version a kept lesson speaks only for its own template
lesson: after a parity override a lesson an order cancelled in the other week does not hold the slot, and the lesson
of this week takes place. Because of that, an edit that would leave a lesson of a live change on a day its group has
no classes (holiday, practice, outside the semester) or in a week its template lesson does not fall on, could not put
a template lesson back because a changed lesson holds its teacher or room, or would make a changed lesson occupy a
room that is taken, is refused (409 with `conflicts[]`): the dispatcher cancels the decision or publishes an amendment
first. Only what the edit changes counts: a lesson that was already missing or out of place before the edit (with
the calendar as it was) does not block it. Publication refuses a change that would put a lesson on such a day.

## 7. Absences, options, changes

A teacher (cabinet, bot) reports absences from today on; earlier days are entered by the dispatcher.

**Affected lessons** = SCHEDULED lessons of the teacher with date in range and pair in `pairs` (or all), plus
lessons the teacher was to take over by a not yet published change (`asSubstitute`). A substitute must not be
absent at (date, pair): checked when the change is created, confirmed and published (409 UNAVAILABLE).

**Options per affected lesson** (endpoint returns them ranked, each with Ukrainian reason chips):
1. `REPLACE_TEACHER` — candidate T must be: not the absent teacher, not absent at (date,pair), free at
   (date,pair) (no SCHEDULED lesson, no CONFIRMED/PENDING change assigning T there), not UNAVAILABLE.
   Score: +50 teaches this discipline (teacher_discipline or any load item with it); +20 same cycle
   commission; +15 closes a window (has lessons before and after that day); +10 already has lessons that
   day; −8 per substitution this month (max −24); −15 UNWANTED slot. Top 10.
   Chips e.g. «Веде цю дисципліну», «Та сама циклова комісія», «Закриває вікно між 2 і 4 парою»,
   «Вже має 2 пари цього дня», «3 заміни цього місяця».
2. `REPLACE_DISCIPLINE` — another load item of the same group whose teacher is free; +20 if that discipline
   is behind plan («Відстає на 6 год»), excluded if already finished.
3. `SWAP` — a later lesson of the same group that day whose teacher is free at this slot («Переставити 4 пару на 2: група піде раніше»).
4. `CANCEL` — «Пари не буде»; note when it is the first/last pair («група прийде пізніше»).
5. `ADDITIONAL` (відпрацювання) — optional: a free slot within 14 days for the group and the absent teacher.

**Approval** (`substitution.approvalMode`, college workflow 08.11.2026): one dispatcher (навчальна частина)
picks every substitution; the substitute is **assigned first and informed afterwards**.
- **NOTIFY** (default): a change with a new teacher is CONFIRMED at once (no deadline, no reminders) and the
  teacher gets «Вам поставили заміну» with [👍 Добре] [❌ Не можу] (Telegram or the cabinet). «Добре» is recorded
  (`change.teacher_answer = CONFIRM`, history entry CONFIRMED → CONFIRMED «Викладач побачив заміну»). «Не можу»
  before the change is in a published order: CONFIRMED → DECLINED (the only way into DECLINED from CONFIRMED,
  taken only by the informed teacher), the lessons are refreshed, the lesson needs a decision again, admin chats
  are told «… Оберіть інший варіант на сторінці змін.». «Не можу» after the order is published never undoes it:
  the objection is recorded (`teacher_answer = DECLINE`, history APPLIED → APPLIED, event
  `substitution.teacher-replied`), admin chats are told that an amendment is needed and the web shows the lesson
  as «Потрібне доповнення» — the dispatcher marks the substitute absent at that pair and picks another option,
  which goes into the amendment. A repeated answer changes nothing; another answer once declined or objected → 409.
  A teacher without Telegram cannot be informed: admin chats get one «{викладач} не підключив Telegram — повідомте
  про заміну самі» per teacher and date (never for past lessons).
- **CONFIRM**: DRAFT → PENDING_CONFIRMATION → CONFIRMED | DECLINED (declined returns the lesson to «needs
  decision»); pending longer than `substitution.confirmTimeoutMinutes` ⇒ reminder (SSE + admin chat).
- **AUTO**: DRAFT → CONFIRMED, the teacher learns from the published order.
Kinds without a new teacher are confirmed at once in every mode.

**Change lifecycle**: DRAFT → PENDING_CONFIRMATION (CONFIRM) or CONFIRMED (NOTIFY, AUTO, no teacher) →
APPLIED; DECLINED from PENDING (CONFIRM) or from CONFIRMED (NOTIFY, the informed teacher only); a declined change
can be asked again (→ PENDING) or cancelled. On order publication CONFIRMED changes of that date become APPLIED: original lesson →
CANCELLED, new lesson (origin CHANGE/ADDITIONAL/BLOCK) created, conflicts re-checked; the lessons a BLOCK replaces
are recorded with it (`change_replaced_lesson`), because a block has no single original. Every status change is
written to `change_history` and pushed via SSE (`change.updated`).

**Reason codes** (short text in the order): `ABSENT` «Відп.», `MUTUAL` «Зв.зам.», `FINISHED` «Вичит.»,
`BUSINESS_TRIP` «Відр.», `OTHER` (free short text). Absence category is private: sick leave is printed as «Відп.»
by default; comments never leave the admin UI.

## 8. Orders (розпорядження)

Draft for date D = all CONFIRMED (not APPLIED) changes with date D + notes from calendar periods active on D
(`order_note`) + room-only changes in the separate section. Header (exact college wording):
```
РОЗПОРЯДЖЕННЯ №{n}
По коледжу про деякі зміни в розкладі занять
На {d} {місяця} {yyyy} р.            e.g. «На 19 жовтня 2026 р.»
{ДЕНЬ ТИЖНЯ} - {ЧИСЕЛЬНИК|ЗНАМЕННИК}  e.g. «ПОНЕДІЛОК - ЗНАМЕННИК»
```
Table: `Група | Пара | За розкладом (дисципліна | викладач) | Причина заміни | По заміні (дисципліна | викладач) | Ауд`,
rows sorted by course, group code, pair. Row types: REPLACEMENT (all filled); CANCELLATION (right side =
merged «Пари не буде»); ADDITIONAL (left = merged «ДОДАТКОВО»); BLOCK (pair «1-4», left side + room, e.g.
ЗУ training day); FINISHED (reason «Вичит.», right side optional); AS_SCHEDULED (left = «За розкладом»);
subgroups → two names / two rooms («304/309»), «(п.)» marks a one-subgroup lesson. Then the section
«ЗАМІНА АУДИТОРІЙ» (group, pair, room), then notes (bold), then signature
`{signatory_position}        {signatory_name}` (settings; e.g. «Заступник директора з НР» / «Микола ПОЛІЩУК»).
Numbering: per semester, sequential from 1, assigned on publication (evidence: №35 on 19.10.2026 is the 35th
school day since 01.09). Changes after publication ⇒ AMENDMENT («ДОПОВНЕННЯ до розпорядження №N»), next number,
only new rows, distributed as a diff.

## 9. Accounting

For semester S, group G, discipline D: `plan = Σ planned_hours(load items)`, `fact = (#SCHEDULED lessons that
already ended) × hoursPerPair`, `remaining = #future SCHEDULED × hoursPerPair`, `forecast = fact + remaining`,
status FINISHED (fact ≥ plan) | WILL_OVERRUN (forecast > plan + 2) | WILL_UNDERRUN (forecast < plan − 2) | ON_TRACK,
`forecastFinishDate` = date where the cumulative reaches plan. Suggested actions: overrun → DISCIPLINE_FINISHED
changes for lessons after the finish date; underrun → ADDITIONAL lessons. Teacher stats per month: substitutions
done (count, hours) and lessons missed.

## 10. Normalization (import, search, bot)

Normalize: NFC, lowercase, Latin look-alikes → Cyrillic (a→а c→с e→е i→і o→о p→р x→х y→у k→к m→м t→т h→н b→в)
when the token is mostly Cyrillic, apostrophes (ʼ ' ` ’) unified, dots/spaces removed for abbreviation keys,
whitespace collapsed. Group code canonical form `^\d[А-ЯІЇЄҐ]{1,3}\d?$`: «ІКІ1» (Cyrillic І typed instead of 1) → «1КІ1»,
«1ТК 1» → «1ТК1», «4КИ1» → «4КІ1». Teacher matching: alias table → exact surname+initials → Jaro-Winkler ≥ 0.92
on surname with compatible initials → suggestions. Real typos in the Excel: «Гарболінська»/«Граболінська»,
«Герасменко»/«Герасименко», «Хорченко О.»/«Харченко О.», «ХарченкоГ.», «Поліщук Л.» vs «Поліщук М.» (different
people!), «Овчар Ін.» vs «Овчар Ів.» (different people). Disciplines: «Укр.мова.»=«Укр.мова», «Фк»=«ФК»,
«Вс.Іст.»=«Вс.іст.», «Вс.»(truncated), «Інфом.»=«Інф.», «Вст.спц»=«Вст.спц.», «Вс.спц.» (likely the same).

Known abbreviations (full names only where certain; otherwise keep the abbreviation as name and set
`needs_review=true` — never invent): Матем.=Математика; Укр.мова=Українська мова; Укр.літ.=Українська
література; Зар.літ.=Зарубіжна література; Ін.мова=Іноземна мова; Вс.іст.=Всесвітня історія; Іст.Укр.=Історія
України; Геогр.=Географія; БіЕ=Біологія і екологія; ФіА=Фізика і астрономія; Хімія; Інф.=Інформатика;
ФК=Фізична культура; ФВ=Фізичне виховання; ГО=Громадянська освіта; Культ.=Культурологія; ЗУ=Захист України;
БЖ=Безпека життєдіяльності; Вст.спц.=Вступ до спеціальності; СТП=Сучасні технології проєктування;
НДЕКСМ (also НДКС)=Надійність, діагностика та експлуатація комп'ютерних систем і мереж; ОПЦЗ=Охорона праці та
цивільний захист; ТПМР=Технології проєктування мережевих ресурсів; ТОЕ=Теоретичні основи електротехніки;
ПДР=Правила дорожнього руху; ІКТ=Інформаційно-комунікаційні технології.

## 11. Source formats

**Excel `seed/schedule-2025-26.xlsx`** — sheets «Розклад друк» (print layout, primary), «пари» (another
version of the same layout, wider), «дзвінки (2 зм)», «дзвінки».
- R2: approvals (director Світлана ВАСИЛЮК, deputy Микола ПОЛІЩУК), title «РОЗКЛАД ЗАНЯТЬ 2025- 2026 н.р.», R4 «І семестр».
- Course blocks: «1 курс» R6, «2 курс» R54, «3 курс» R102, «4 курс» R150. In each block: curators row
  (fill `FF66FFFF`), group header row (fill `FF00FFFF`): group code in the group's 1st column, home room in its
  3rd column; then 5 day blocks (col A merged: ПОНЕДІЛОК…П'ЯТНИЦЯ), pair numbers in col B («1.», «3», «5.» …),
  **two rows per pair**, then a blank separator row.
- Each group = 3 consecutive columns from C: [discipline, teacher, room]. Course 1: 16 groups (C..AX),
  courses 2–3: 15, course 4: 10 (4ЕА1 4ЕА2 4ВЕ 4РТ1 4РТ2 4КІ1 4КІ2 4КІ3 4ОП1 4ОП2).
- Two-row semantics: value merged over both rows → BOTH; only top row → NUMERATOR; only bottom → DENOMINATOR;
  discipline merged but teacher/room differ per row → **subgroups 1/2** of the same discipline; different
  disciplines top/bottom → NUM/DEN alternation; «Крисак Л./Можаровська» + «110/12» in one row → subgroups;
  «Харченко О.(п)» → one-subgroup lesson. Use merged ranges (openpyxl/POI) — not cell colors (yellow = cosmetic).
- Rooms: numbers or «с.з.»/«С.З.» (sports hall, max_parallel 3), «с.п.» (sports ground).
- 2nd course rows use pairs 5–8 (Mon–Thu) and 4–6 (Fri) — shift 2.

**Order photos** `seed/orders/*.jpg`: №35 (19.10.2026 ПОНЕДІЛОК - ЗНАМЕННИК), №96 (22.05.2026 П'ЯТНИЦЯ - ЗНАМЕННИК),
№105 (04.06.2026 ЧЕТВЕР - ЗНАМЕННИК). Layout §8. Example rows (№35): `1ТК1 | 1-4 | ЗУ Ємельяненко | | | 204`;
`1КІ2 | 2 | Ін. мова Крисак Л. | Відп. | Ін.мова Прокопчук | 110`; `4КІ1 | 2 | СТП Кренцін | Зв.зам. | НДКС Непийвода | 6`;
`1ТК1 | 1 | Ін. мова Крисак Л. (п.) | Відп. | Пари не буде`. Notes: «2КІ3 – Електрорадіомонтажна практика з 19.10.26р. по 30.10.26 р.»,
«Групи 4КІ з 01.09.26р. по 31.10.26 навчаються – дистанційно.». Signature «Заступник директора з НР  Микола ПОЛІЩУК».

**Discipline programs** `seed/programs/*` (DOCX, DOC): all contain the table «Структура навчальної дисципліни
загальна й за семестрами» with header rows `Кількість годин | Розподіл по семестрах та форми контролю` /
`Всього | На заняттях | СРС | Семестр N денна форма навчання` / `… Лекції | Практ. | Лабор. | Семінар. | Іспит | Залік | Конс.`
and one data row (e.g. `120 | 40 | 80 | 20 |  | 20 |  |  | х |`). Data is dirty: «відведено 180 годин» in text vs
150 in the table; per-topic labs sum 18 vs 20; semester missing or outdated («Семестр 5» for a 4th-course
discipline) ⇒ the importer validates sums, never trusts the semester, and the admin binds program → group/semester.

**Curriculum** (Google Drive scan, 5 pages): «План освітнього процесу» with columns per semester:
`годин на тиждень | годин на семестр` and «N тиж.» headers (sem 1: 17 тиж., 2: 23, 3: 16, 4: 16, 5: 15, 6: 17, 7: 22).
Vision extraction target: rows {discipline, semester, hoursPerWeek, hoursPerSemester, lectures, practice, labs, controlForm}.

## 12. Demo seed

- Semester «I семестр 2026/2027»: 2026-09-01 … 2026-12-30, first week NUMERATOR, bell profile «2 зміни, 60 хв»;
  semester II 2027-01-19 … 2027-06-30 (empty).
- Catalog + base timetable imported from the Excel (`seed/schedule-2025-26.xlsx`, sheet «Розклад друк») as the
  published version effective 2026-09-01 (README notes the data is from 2025/26). Load items derived from the
  timetable (`TIMETABLE_DERIVED`: BOTH = 1 pair/week, NUM/DEN = ½) — feeds generation and accounting.
- Departments: Комп'ютерне (КІ), Фінансово-економічне (Ф, ОБ, М, ЕП, ОД), Радіотехнічне (РТ, ТК),
  Електроенергетики та електромеханіки (ЕА, ВЕ), Транспортних технологій (ОП).
- Calendar periods from order notes (practice 2КІ3 19.10–30.10.2026; 3rd course online 01.09–31.10.2026;
  4КІ online 01.09–31.10.2026), autumn holidays 2026-10-26…2026-10-30 are NOT assumed (unknown) — leave none.
- Demo accounts linked to teachers «Кренцін» and «Непийвода». A few demo absences/changes reproducing order №35.
