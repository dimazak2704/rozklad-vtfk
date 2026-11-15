# scheduler — timetable generator (Timefold Solver 2.8)

`services/scheduler` (`ua.vtc.rozklad.scheduler`, port 8082). It turns a `ProblemSnapshot` from core
into a weekly timetable: every unit gets a timeslot (weekday × pair), a room and, for a HALF unit,
a parity (numerator or denominator week). It never touches the core database: everything arrives in
`generation.requested`, and the result leaves in `generation.completed`.

## 1. Responsibilities and flow

```
core ──generation.requested──► scheduler.q.generation ──► GenerationIntake
                                                           │ job row QUEUED (own DB), problem built
                                                           ▼ after commit
                                    SolverManager (Timefold) ──started──► job RUNNING
                                           │ new best timetables ──► ProgressTracker ─(≤ every 2 s)─► generation.progress
                                           ▼ final best / error
                                    GenerationLifecycle: SOLVED | CANCELLED | FAILED + payload stored
                                           └─(after commit, broker confirm)─► generation.completed
core ──generation.cancel──► GenerationIntake ──► terminateEarly(jobId)
```

| Package | What lives there |
|---|---|
| `solver.domain` | Timefold model: `Timetable` (`@PlanningSolution`), `LessonUnit` (`@PlanningEntity`), facts `Timeslot`, `Room`, `Teacher`, `StudentGroup`, `ShiftWindow`, CH difficulty `LessonUnitDifficulty` |
| `solver.score` | `TimetableConstraintProvider`, `TimetableConstraint` (ASCII id + Ukrainian title + kind), incremental collectors (`DayCollectors` over `GroupDay`, `TeacherDay`, `TeacherWeek`, `DisciplineDay`; `LaneCounts`, `WeekBalance`), `ConstraintWeights`, `TimetableAnalyzer` |
| `solver.move` | `SiblingMoveIteratorFactory` + `SiblingChangeMove`: the subgroup copies of one occurrence move together |
| `solver` | `TimetableProblemFactory` (snapshot → model, value ranges, room warm start), `ProblemSnapshotValidator`, `TimetableResults` (model → event payload) |
| `generation` | job entity/repository, `GenerationIntake` (requests, cancels), `GenerationSolver` (SolverManager), `ProgressTracker`, `GenerationLifecycle` (state + completion), `JobMaintenance` (restart recovery, re-send, purge), RabbitMQ listener, REST controller |
| `messaging` | `EventPublisher` (after commit; completion with publisher confirms), `ProcessedEvents` (dedupe by `eventId`) |

## 2. Planning model

* **Entity** `LessonUnit` — one weekly lesson of the snapshot. Variables: `timeslot`, `room`,
  `parity`. Value ranges are **per entity** (`@ValueRangeProvider` on the entity):
  * timeslots: the group's shift window ∩ snapshot timeslots, minus the teacher's UNAVAILABLE slots
    (if that leaves nothing, the whole window — the hard constraint then reports it);
  * rooms: rooms of the required type; whole-group lessons only in rooms with capacity ≥ group size
    (unknown capacity/size = fits); the sports hall and sports ground (`GYM`, `SPORTS_GROUND`) only for
    units that require them, name them as preferred, teacher's or home room, stood there in the base version, or
    belong to a load item whose base lessons with a room all stood there (`baseRoomTypes`: those rooms come first in
    the range and get the warm start, ordinary rooms stay in the range for the soft rule `specialRooms`); an item
    without any base lesson in a room (from scratch) whose preferred or teacher's usual room is the hall or the
    ground gets both types the same way (the PE teacher's room is the hall); if nothing fits, every room (reported
    by the hard constraint);
  * parity: `BOTH` for WHOLE units, `NUMERATOR`/`DENOMINATOR` for HALF units.
  Timefold 2.8 validates assigned values against these ranges, so swaps that would break a range are
  never made.
* **Pinned units** (`pinned != null`) are `@PlanningPin` with the pinned slot, parity and room; a pin
  without a room uses the placeholder `Room.NONE` (occupies nothing) and comes back with `roomId: null`.
  An **online** pinned unit (`online: true`, DOMAIN §5) also stands in `Room.NONE` — no room
  occupancy, capacity or room type is checked for it — and comes back with its pinned room unchanged
  (`LessonUnit.keptRoomId`), so core keeps the room for reference.
* **Student cohorts**: a group without subgroups is one cohort; a split group has cohorts = its
  subgroups, and a whole-group lesson belongs to all of them. Up to 8 subgroups per group.
* **Base placement**: `initial` of a free unit is where it stands in the base version. It is the warm
  start (when the slot and parity are inside the value ranges) and it stays on the unit
  (`LessonUnit.baseSlot/baseParity/baseRoomId`) for the stability rules, even when it is out of range.
* **Siblings**: units with an equal `siblingKey` are the subgroup copies of one weekly occurrence of a
  split load item (`loadItemId:w1`, `:w2`, `:h`). The factory keeps a key only on at least two free units
  of one group with distinct subgroups; any other use is dropped with a warning (a core data error should
  not mislead the subgroup rules or fail the job). Copies of two teachers must share the slot and the weeks (hard
  «Subgroups not together», owner's rule of 15.11.2026); one teacher's copies cannot, so they should stand on
  neighbouring pairs of one day in the same weeks (soft «Subgroups apart», `subgroupsTogether`, default 30). Pinned
  units carry no key, so a pinned pair is never scored. A key whose copies cannot all be parallel by the data is
  soft as a whole (`LessonUnit.softSiblingRule`, a warning in the log): one teacher teaches two of three or more
  copies (A, A, B: B cannot be parallel to both of A's copies), or the copies share no allowed slot or parity (their
  teachers' free slots never meet in the group's shift). Its pairs fall to «Subgroups apart», where two teachers'
  copies count as together in one slot or on neighbouring pairs. Real data has neither: all 72 keys of the four real
  requests have two copies of two teachers with common slots. The sibling move does not help such a key (it moves
  copies with a common slot only; A, A, B go to three consecutive pairs).
* **Teachers**: `maxPairsPerDay` from the snapshot (`null` = no limit) and a **baseline of teaching days**
  per kind of week: the weekdays of the teacher's base (`initial`) and pinned lessons in that week, but at
  least `ceil(load / limit)` days (load = whole units + half of the halves, limit 4 when the snapshot has
  none). A teacher with only new units gets the load-based baseline.
* **Week arithmetic**: BOTH meets in both week types, a half in one. Penalties count week
  occurrences (two BOTH lessons in one slot = 2, BOTH vs a half = 1, numerator vs denominator = 0).
* **Room warm start**: pinned rooms and the base rooms of started units take their slot first. A
  started unit whose base lesson had **no room** takes a compatible room that is **free at its start
  slot** — the desired room if it is free, else the least loaded free one (B2: the published version has
  66 room-less lessons; putting them blindly into the teacher's room started every run with 4–120 hard
  conflicts the base did not have). Every other unit gets its desired room (preferred room of the load
  item, else the teacher's usual room `teacherRoomId`, else the home room of an older snapshot — the
  first compatible one) or the least loaded compatible room. The construction heuristic then places
  only timeslot × parity (≈20–40 moves per unit instead of ≈2000 with rooms in the cartesian product);
  local search changes rooms freely.

## 3. Constraints

Constraint ids are ASCII because Timefold 2.8 accepts only `[a-zA-Z0-9 _.'()-]` there; the analysis
in `generation.completed` uses the Ukrainian titles.

| Id | Title (UI) | Level | Match / penalty | Request weight |
|---|---|---|---|---|
| Teacher conflict | Накладка у викладача | hard | pair of lessons of one teacher in one slot, shared weeks | — |
| Group conflict | Накладка в розкладі групи | hard | pair of overlapping cohorts (0 vs any, k vs k) in one slot, shared weeks | — |
| Room conflict | Накладка в аудиторії | hard | room × slot: lessons above `maxParallel` in the numerator + denominator week | — |
| Room type mismatch | Невідповідний тип аудиторії | hard | unit, week occurrences | — |
| Room too small | Аудиторія замала для групи | hard | whole-group unit, week occurrences | — |
| Outside shift window | Пара поза межами зміни | hard | unit, week occurrences | — |
| Teacher unavailable | Викладач недоступний у цей час | hard | unit, week occurrences | — |
| Subgroups not together | Підгрупи дисципліни не одночасно | hard | two copies with one sibling key (at least one free) and different teachers not in one slot with one parity; the larger week occurrences of the two. A key whose copies cannot all be parallel by the data (one teacher for two of three or more copies; no common allowed slot or parity) is left to «Subgroups apart» (§2 «Sibling keys») | — |
| Group windows | Вікна в розкладі групи | soft | group-day: free pairs between lessons, per kind of week the **worst cohort** (B10; the core metric does the same), summed over both kinds of week | `windows` |
| Teacher windows | Вікна у викладача | soft | teacher-day: free pairs between lessons per kind of week, **except waits between shifts** | `teacherWindows` (absent → `windows`) |
| Teacher shift wait | Очікування викладача між змінами | soft | teacher-day: free pairs of a gap a < b where every lesson at a is with groups of one shift and every lesson at b with groups of the other shift (one definition with the core metric) | `teacherShiftWait` |
| Broken day | Рваний день (чисельник і знаменник різні) | soft | group-day: \|Δ pair count\| + 1 if the first pair differs | `brokenDays` |
| Late pair | 4-та пара зміни | soft | unit on the 4th pair of its shift window (`contracts.LatePairs`) costs 1, the 5th 2, …, × week occurrences; Friday shift 2 (pairs 4–6) has none; off by default (owner: the 4th pair is normal) | `latePairs` |
| Group one pair day | День з однією парою в групи | soft | group-day: kinds of week with exactly one occupied pair (union of cohorts) | `studentOnePairDay` |
| Subgroup alone | Підгрупа на парі, а інша без заняття | soft | group-day: pair × kind of week where a subgroup lesson is active and some cohort of the group has no lesson (next to the hard rule it covers subgroup lessons without a counterpart in the load and crossed disciplines) | `subgroupsTogether` |
| Subgroups apart | Підгрупи не підряд, де одночасно неможливо | soft | two copies with one sibling key (at least one free) and one teacher not on neighbouring pairs of one day with one parity (alternating numerator/denominator in one slot counts too); in a key that cannot be parallel by the data, two teachers' copies neither parallel nor on neighbouring pairs too; the larger week occurrences | `subgroupsTogether` |
| Teacher one pair day | День з однією парою у викладача | soft | teacher-day: kinds of week with exactly one occupied pair | `teacherDays` |
| Teacher extra day | Зайвий робочий день викладача | soft | teacher: per kind of week `max(0, teaching days − baseline)` (§2) | `teacherDays` |
| Teacher overload | Забагато пар на день у викладача | soft | teacher-day: pairs above `maxPairsPerDay` per kind of week | `teacherOverload` |
| Discipline twice | Дисципліна двічі на день | soft | group-day-discipline: pairs above 1 per kind of week for the busiest cohort (two subgroups of one teacher one after the other are one pair for each student) | `disciplineTwice` |
| Discipline repeated | Дисципліна три й більше разів на день | soft | group-day-discipline: distinct pairs above 2 per week type | config (5) |
| Unwanted slot | Небажаний для викладача час | soft | unit, week occurrences | `preferences` |
| Room preference | Заняття не в аудиторії викладача | soft | unit outside its desired room (load item's preferred room, else the teacher's usual room), week occurrences | `homeRoom` |
| Unit moved | Пару переставлено відносно базової версії | soft | unit whose slot or parity differs from its base placement, week occurrences | `stability` |
| Room changed | Пара в іншій аудиторії, ніж у базовій версії | soft | unit in its base slot and parity but not in its base room (only when the base had a room), week occurrences | `stability` |
| Special room lost | Пара не в спортзалі чи на спортмайданчику | soft | free unit with `baseRoomTypes` (§2: its item's base lessons with a room all stood in GYM/SPORTS_GROUND rooms, or, without a base, its teacher's or preferred room is one) placed in a room of another type, week occurrences (data-driven, never by discipline name; replaces core's derived hard room type, B11) | `specialRooms` |
| Halves paired | Чисельник і знаменник в одному слоті (бонус) | soft **reward** | two halves of one cohort in one slot with different parities | `brokenDays` |

**Owner's answers (15.11.2026, DOMAIN §5, §13).** The subgroups of a split discipline at the same time is **hard** for
copies of two teachers: the real timetable keeps it in all 72 sibling pairs (all of two teachers), a run from the
published version starts and stays at 0 hard, and from scratch the run reached 0 hard far sooner than with the old soft
rule (§11 «Owner's rules»). One teacher cannot teach both copies at once, so for them the neighbouring pairs are a
**soft rule with the strongest default** (`subgroupsTogether` 30): the published timetable has no such case, and hard
neighbouring pairs left the synthetic full second shift (interlocked labs of one teacher) infeasible in 3 of 3 runs,
while soft at 30 reaches 0 hard in every run. A group's one-pair day and PE in the hall are confirmed rules kept
**soft** (`studentOnePairDay` 10, `specialRooms` 30): the hall has 5 places and the published timetable fills all 5 in
4 slot-weeks, so a full or closed hall must cost points, not make the run infeasible. The web does not offer these three
weights as priorities. The 4th pair of the shift is normal: «4-та пара зміни» defaults to 0 and stays switchable.

The other rules added after the generator review (shift wait, teacher days and overload, a discipline twice,
stability) are **preferences with a weight**: `0` switches them off, and the defaults come from the college's real
timetable (CONTRACTS «GenerationWeights»): the published timetable has 0 student windows in 560 group-days but 309
teacher gap pairs, 186 of them between the shifts; 3 student one-pair days; 98 of 781 teacher day-weeks with one pair
and 35 with 5–6 pairs; a discipline twice a day on 12 of 560 group-days; all 60 PE items stand only in the hall or on
the ground (none in a classroom). Rules over one day share a grouping node (`GroupDay`, `TeacherDay`,
`DisciplineDay`), so adding them did not add passes over the units.

Weights: `GenerationWeights.DEFAULT` = windows 30, brokenDays 10, latePairs 0, preferences 5,
homeRoom 1, teacherWindows 10, teacherShiftWait 3, stability 1, subgroupsTogether 30, studentOnePairDay 10,
teacherDays 5, teacherOverload 3, disciplineTwice 10, specialRooms 30 (the request keeps the name `homeRoom`; since round 7
it weights the teacher's room), tuned on replays of the real requests (§11 «Default weights», «Owner's rules»). A request of
the first schema (five weights) gets the defaults for the rest and `teacherWindows = windows`. `0` switches a constraint off
(`ConstraintWeightOverrides`). Hard constraints are not weighted. The day rules use custom incremental
collectors over «lanes» (cohort × week type) — `TimetableSolverTest` runs the solver in `FULL_ASSERT`
(also with base placements, siblings and daily limits) to prove the incremental score equals a full
recalculation.

**Score analysis.** `SolutionManager.analyze` (ScoreAnalysis) is a commercial feature in Timefold 2.8
and throws in the Community edition. `TimetableAnalyzer` therefore computes matches and scores per
constraint with the same domain rules and the same match granularity; `TimetableAnalyzerTest`
checks, on a planted and on a deliberately broken timetable (every hard constraint violated), that
each constraint's impact equals the solver's (`ConstraintVerifier`) and that the sum equals the
solver's score. The analysis always lists all 27 constraints (hard first); a switched-off
constraint shows its matches with score `0hard/0soft`. Each row also carries the ASCII `constraintId`,
`bonus` (true for «Halves paired», which the UI shows as achieved, not as a problem) and
`scopeMatchCount` (B7): the matches that involve at least one free unit — for a group-day, teacher-day
or group-day-discipline match, the group or teacher has a free unit that day; for «Teacher extra day»,
the teacher has a free unit. So the pinned lessons of groups outside the scope no longer look like
problems of the run.

## 4. Solver configuration (`src/main/resources/solverConfig.xml`)

* Construction heuristic: `FIRST_FIT_DECREASING` over the cartesian product timeslot × parity (rooms
  are warm-started). Difficulty (`LessonUnitDifficulty`): WHOLE before HALF (halves then pair up in
  the remaining slots), subgroup copies with a sibling key (hard rule), scarce rooms first (sports hall,
  labs), then busy teachers, then units with fewer allowed timeslots. The copies of one sibling key take the
  difficulty of the hardest of them and are placed one after another, so the second copy finds the slot of the
  first still free: in the old order the synthetic real-shaped college stayed at −2…−6 hard after 180 s (5 runs), in
  the new one it reaches 0 hard in 22–153 s (7 runs, §11 «Owner's rules»).
* Local search: late acceptance (400) with union of change (timeslot, room, parity) and swap
  (timeslot, room) moves plus the **sibling move** (`SiblingMoveIteratorFactory`, M3): all free units of
  one sibling key go to one slot and parity in one step (parallel when their teachers differ, neighbouring
  pairs of one day when one teacher teaches two of them), so subgroups that study together stay together
  while they move; an ordinary move of one copy of two teachers breaks the hard rule, so the sibling move is the
  way such copies move. A random sibling move also gives a copy whose room is taken at the target a free room of
  its range (its desired room first): without it a pair could only move into slots where both its rooms happened
  to be free. In random selection order the union gives each child selector the same chance (Timefold's default
  without `fixedProbabilityWeight`), so about one move in six is a sibling move. `acceptedCountLimit` 16 (chosen by
  the benchmark in §11).
* Termination per job (`SolverConfigOverride`): the requested `timeLimitSeconds`, or earlier when the
  best score has not improved for `max(15 s, 25 % of the limit)` (`JobTermination`).
* `timefold.solver-manager.parallel-solver-count` = `SCHEDULER_PARALLEL_SOLVERS` (default 1); further
  jobs wait as QUEUED. Multi-threaded solving is an Enterprise feature and is not used.

## 5. Events, idempotency, failures

* Listener on `scheduler.q.generation` (+ `.dlq`): `GenerationRequested` and
  `GenerationCancelRequested` (dispatch by the `__TypeId__` header written by the shared Jackson
  converter); other payloads and unreadable messages go to the DLQ after the container retries.
* **Idempotent**: every event id is stored in `processed_event`; a second request for a known
  `jobId` is ignored. The job row (`generation_job`, migration `V2__generation_job.sql`) is the
  idempotency key; the solver starts only after its transaction commits.
* **Progress**: the solver reports each new best timetable; only complete timetables count. The
  tracker keeps the latest per job and every `app.scheduler.progress-interval` (2 s) sends it if it
  changed (`elapsedMs` = solving time when that score was found) and stores it in the job row.
* **Completion**: the terminal status and the full `generation.completed` payload are stored in one
  transaction under a row lock (the solver callback, a cancel and a restart may race; the first wins).
  After the commit the event is sent with a **per-message publisher confirm**
  (`publisher-confirm-type: correlated`, `CorrelationData` = event id; `simple` confirms were dropped
  because `waitForConfirms` also reports NACKs of earlier messages on a cached channel); if the broker
  does not confirm, `JobMaintenance` re-sends the stored payload with the same `eventId` every 30 s.
* **Request id** (CONTRACTS §7): the listener factory puts `X-Request-Id` and `message_id` of
  `generation.requested`/`generation.cancel` into the MDC (`requestId`, `eventId`; a new id when the header
  is missing); the solver callbacks run with the MDC of the request that started the job, so
  `generation.completed` carries the same `X-Request-Id` and every sent event has `message_id` = `eventId`.
  Periodic progress and the completion re-sent by `JobMaintenance` carry no request id.
* **Statuses**: SOLVED — time limit or plateau reached, all units placed (may still have hard
  violations: `hardScore < 0`, the analysis shows which). CANCELLED — after `generation.cancel`; a job
  that was already solving keeps its best timetable (assignments present), a queued or not yet placed
  one has none. FAILED — invalid snapshot (all problems listed in Ukrainian), time limit above
  `app.scheduler.max-time-limit`, the time ran out before every unit was placed, a solver error, or the
  service restarted/stopped during the job. Scores are 0 when a job has no timetable.
* **Cancel before request**: an unknown `jobId` is recorded as CANCELLED (and announced), so the
  late request is ignored.
* **Restart**: jobs received by an earlier process and still QUEUED/RUNNING fail with «Генерацію
  перервано: сервіс планувальника перезапускався…». A job stopped by a shutdown is reported the same
  way, not as a shortened SOLVED result. Single instance assumed.
* Finished jobs and processed event ids are purged after 30 days (03:40 Kyiv).

## 6. REST (role ADMIN)

| Method & path | Response |
|---|---|
| `GET /api/scheduler/jobs/{id}` | `{id, semesterId, status, receivedAt, startedAt, finishedAt, timeLimitSeconds, unitCount, bestScore, hardScore, softScore, elapsedMs, cancelRequested, error, resultDelivered}`; 404 ProblemDetail «Задачу генерації … не знайдено.» |
| `GET /api/scheduler/status` | `{queuedJobs, runningJobs}` |

## 7. Configuration

| Property (env) | Default | Meaning |
|---|---|---|
| `timefold.solver-manager.parallel-solver-count` (`SCHEDULER_PARALLEL_SOLVERS`) | 1 | jobs solved at once |
| `app.scheduler.progress-interval` (`SCHEDULER_PROGRESS_INTERVAL`) | 2s | throttle of `generation.progress` |
| `app.scheduler.max-time-limit` (`SCHEDULER_MAX_TIME_LIMIT`) | 15m | longer requests fail |
| `app.scheduler.min-unimproved-limit` / `unimproved-ratio` | 15s / 0.25 | plateau termination |
| `app.scheduler.discipline-repeat-weight` (`SCHEDULER_DISCIPLINE_REPEAT_WEIGHT`) | 5 | weight the request does not carry |
| `app.scheduler.completion-resend-interval` | 30s | re-send of unconfirmed completions |
| `app.scheduler.retention` | 30d | purge of finished jobs / processed ids |
| `timefold.solver.termination.spent-limit` | 15m | safety net; every job sets its own limit |

## 8. What core should put into the snapshot

| Field | Source in core |
|---|---|
| `timeslots` | slots of the semester's bell profile (weekday × pair) |
| `shiftWindows` | table `shift_window` (every shift used by a group needs its windows) |
| `groups` | active groups in scope: `size` (0 if unknown), `shift`, stored subgroup numbers |
| `rooms` | active rooms: `capacity` (0 if unknown), `max_parallel`, `type` |
| `teachers` | every teacher referenced by a unit, with `teacher_preference` UNAVAILABLE / UNWANTED of the semester |
| `units` | load items split by DOMAIN §4 (whole pairs + at most one HALF), one unit per subgroup for split items with that subgroup's teacher; `requiredRoomType`/`preferredRoomId` from the load item; `teacherRoomId` (round 7) = the teacher's usual room for the discipline in the published version (DOMAIN §5); `homeRoomId` null since round 7 |
| `units[].initial` | the unit's place in the base version (slot, parity, room): warm start, stability baseline and teacher-day baseline |
| `units[].siblingKey` | `loadItemId:suffix` on the free subgroup units of one occurrence of a split item (2+ subgroups), else `null` |
| `teachers[].maxPairsPerDay` | the college setting `generation.teacherMaxPairsPerDay` for every teacher (`null` = no limit, older cores) |
| `units[].pinned` | pinned template lessons of `baseVersionId`, and lessons of groups outside the scope that must stay as they are |
| `units[].online` | the template lesson's online flag, for pinned units only (`PlanUnit.toSnapshotUnit`); an in-scope unit pinned at a base lesson takes that lesson's flag, so the generated lesson keeps it |

## 9. Contract notes (no change to `libs/contracts`)

* `analysis[].constraint` is the Ukrainian title, `analysis[].constraintId` the ASCII id, `bonus` marks
  the reward row and `scopeMatchCount` counts matches with a free unit (§3). `analysis[].score` is
  `HardSoftScore.toString()` (`-2hard/-30soft`), weights included.
* CANCELLED may carry assignments (best timetable before the cancel) — core may offer «apply anyway».
* `hardScore`/`softScore` are 0 for FAILED and for CANCELLED without assignments.
* Pinned units without a room come back with `roomId: null`; units of other groups that core wants
  respected (out of scope of a partial generation) should be sent as pinned units.
* `ProblemSnapshot` has no stream id, so «one lesson for several groups» is not modelled: two units of
  a stream would be treated as a teacher conflict.
* Round 7 (additive in `libs/contracts`): `units[].teacherRoomId` — the teacher's usual room. The desired room
  is the first compatible of `preferredRoomId`, `teacherRoomId`, `homeRoomId`; a GYM/SPORTS_GROUND named there
  joins the unit's room range. Older snapshots without the field read it as null. Measured on the real data
  (2КІ1, 2КІ2, 3КІ1, 3КІ2, 45 s, published base): see CHANGELOG-CONTRACTS «Round 7».
* Snapshot validation is strict: unknown group/teacher references, a pin in an unknown room, subgroups
  the group does not have, a shift without windows, pins outside the timeslots, duplicate ids, no rooms →
  FAILED with the list of problems. Desired rooms (`preferredRoomId`, `teacherRoomId`, `homeRoomId`) are
  preferences: one that is not in `rooms[]` (core sends only active rooms, but a load item may still name
  a deactivated one) is ignored with a log warning, so one stale load item cannot fail generation for
  the whole scope.
* Additive `units[].online` (absent = false): an online pinned unit occupies no room and gets its pinned
  room back in `assignments` (see §2). A free unit is always placed in a room, whatever the flag.

## 10. How to test

```bash
source scripts/env.sh
mvn -q -pl services/scheduler -am verify                               # unit + Testcontainers ITs
mvn -q -pl services/scheduler -am test -Dtest='TimetableConstraintProviderTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -q -pl services/scheduler -am test -Dtest=RealSizeBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dbenchmark=true [-Dbenchmark.seconds=90] [-Dbenchmark.config=file:/path/to/solverConfig.xml]
mvn -q -pl services/scheduler -am test -Dtest=SnapshotReplayTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dscheduler.excludedTags=none -Dreplay=college-from-published:180,synthetic:tight-shift2 \
    [-Dreplay.weights=latePairs=3] [-Dreplay.seed=1] [-Dreplay.out=results.jsonl]
```

* `TimetableConstraintProviderTest` — ConstraintVerifier for every constraint (positive and negative
  cases, parity overlap, subgroups, worst cohort, shift waits per kind of week, daily limits and
  baselines, stability, `maxParallel`, pins without a room) and request weights (old and new). `SubgroupsTogether`:
  the hard rule for copies of two teachers (one slot and parity; other slots or weeks break it), the soft one for one
  teacher (neighbouring pairs; a gap, another day or alternating numerator/denominator are penalized), the pinned
  exemption and that no request weight changes the hard rule.
* `TimetableAnalyzerTest` — analysis = solver, constraint by constraint (every constraint is in the
  cross-check map), on a planted, a perturbed (with base placements) and a hand-made week where every new
  rule matches; scope matches of pinned-only matches are 0.
* `SiblingMoveIteratorFactoryTest` — sibling groups (shared slots, pinned and lone keys), parallel vs
  neighbouring targets, free rooms at the target (desired room first, undone cleanly), a `FULL_ASSERT` run with
  sibling moves only.
* `RealSnapshotGateTest` (opt-in, `-Dgenfix.snapshots=<dir>|classpath`) — speed gate, see §11.
* `SnapshotReplayTest` (opt-in: tag `replay`, which the build excludes, plus `-Dreplay=<datasets>`) — replays a
  `generation.requested` with the production solver config and termination and logs the generator study's table
  (reference, first placed, final): score, time to 0 hard, group windows, broken days, 4-та пара, one-pair days,
  lone subgroup slots, a discipline twice, teacher windows, waits between shifts, teaching days, one-pair and
  5+-pair teacher days, unwanted slots, PE outside a sports room, moved units, unplaced units, plus the solver's own
  count next to each metric. Datasets: the four real requests of 15.11.2026 (gzipped in
  `src/test/resources/replay`, completed the way this branch's core sends them), a JSON file, or
  `synthetic:<shape>[@warm]` (`ShapedCollege`). Batches: `SnapshotReplay.main` on the test classpath
  (`datasets=… weights=name=v,… label=… out=results.jsonl seed=…`).
* `ReplayMetricsTest` — the harness's metric definitions on a hand-made week (hand-counted values), including
  `sibApart` (copies of one sibling key not together, the hard rule counted independently), `loneScope` (lone subgroup
  slots of groups with a free unit), `grp2p` (two-pair group days) and `spread` (most minus fewest pairs over the
  weekdays of a group, a balance measure).
* `ShapedCollegeTest` — every synthetic shape plants a timetable without hard violations and has its feature
  (pairs a week, a full second shift, parallel and crossed subgroups with sibling keys, UNAVAILABLE/unwanted
  slots and part-time teachers, pinned streams with followers without teacher and room).
* `TimetableProblemFactoryTest` — value ranges, warm start (room-less base lessons start in a free room),
  base kept out of range, sibling keys, teacher limits and baselines, pins, all 13 weights and the old
  five, an old snapshot JSON without the new fields, Ukrainian validation errors, unknown desired rooms
  ignored, online pins occupying no room and keeping it in the result.
* `TimetableSolverTest` — a synthetic college (24 groups × 16 units, two shifts, subgroups, sports
  hall with 3 places) reaches 0 hard; pins stay; an all-pinned problem is returned unchanged;
  `FULL_ASSERT` run.
* `HalfPairingTest` — the 4КІ1 case: two scattered halves give 2 broken days, one shared slot gives 0;
  the solver pairs them by itself.
* `TimetableResultsTest` — assignment order, room-less pins, analysis rows and titles.
* `GenerationJobTest`, `JobTerminationTest`, `ProgressTrackerTest` (throttling, only the latest score),
  `GenerationLifecycleTest` (SOLVED / CANCELLED with and without a timetable / not placed / shutdown /
  a second finish / unconfirmed delivery / progress only for running jobs).
* `GenerationMessagingIT` (PostgreSQL + RabbitMQ in Docker) — solve with progress and analysis,
  duplicate events/job ids, cancel of a running job, cancel before request, invalid snapshot, time limit
  too long, restart recovery, re-send of an unconfirmed completion, DLQ, REST.
* `SyntheticCollege` (test support) builds a ВТФК-shaped college and **plants** a feasible timetable
  first, so every generated problem is known to have a 0-hard solution. `ShapedCollege` does the same with the
  real shape (14–19 pairs a week, days from the first pair, a lighter Friday, one teacher per load item, parallel
  and crossed subgroups) in five shapes: `real-shaped` (56 groups), `tight-shift2` (12 second-shift groups with
  18–19 pairs for 19 slots, a gym with 2 places), `many-splits`, `teacher-limits` (40 % of teachers with
  UNAVAILABLE slots, half with unwanted ones, two part-time teachers), `small-stream` (6 groups, two pinned
  streams, PE without a required type).

Manual run next to other branches (own database and vhost on the shared infra, port 18082):

```bash
docker exec rozklad-postgres-1 psql -U postgres -c "CREATE USER scheduler_feat WITH PASSWORD 'scheduler_feat'"
docker exec rozklad-postgres-1 psql -U postgres -c "CREATE DATABASE scheduler_feat OWNER scheduler_feat"
docker exec rozklad-rabbitmq-1 rabbitmqctl add_vhost scheduler-feat
docker exec rozklad-rabbitmq-1 rabbitmqctl set_permissions -p scheduler-feat rozklad ".*" ".*" ".*"
mvn -q -pl services/scheduler -am package -DskipTests
SERVER_PORT=18082 DB_NAME=scheduler_feat DB_USER=scheduler_feat DB_PASSWORD=scheduler_feat \
  SPRING_RABBITMQ_VIRTUAL_HOST=scheduler-feat java -jar services/scheduler/target/app.jar
```

Publish a `generation.requested` (header `__TypeId__ = ua.vtc.rozklad.contracts.events.GenerationRequested`)
to `rozklad.events` in that vhost, e.g. through the management API, and read `generation.*` from a
**durable** queue bound there (RabbitMQ 4.4 rejects transient non-exclusive queues);
`GET /api/scheduler/jobs/{id}` needs an ADMIN token (`python scripts/rozklad_auth.py token --user admin`). Done once for this module: two
groups / 17 units, 8 s limit → one `generation.progress`, `generation.completed` SOLVED `0hard/4soft`
with 17 assignments and 15 analysis rows, REST 200 with `resultDelivered: true`; the database, role
and vhost were dropped afterwards.

## 11. Benchmark (real college size)

Since the genfix evaluation `RealSizeBenchmarkTest` builds `ShapedCollege.Shape.realShaped()`: 56 groups with
14–19 pairs a week (1080 units, about 200 subgroup units, a gym with 5 places), solved from scratch. It is harder
than the old fixture: with the first default weights it reached 0 hard after 110 s, with the tuned ones after
33 s (single 180 s runs, 4 JVMs on the laptop); alone in the surefire JVM with the tuned weights: 1080 units (176
halves, 152 subgroup units), 121 teachers, 62 rooms, 0 hard after 7.4 s, 0hard/−5338soft after 90 s. The table below is the history of the old fixture
(`SyntheticCollege.realSize()`), which chose the local search settings.

`SyntheticCollege.realSize()`: 56 groups (41 first shift, 15 second
shift), 16 units each = **896 units** (112 halves, 52–60 subgroup units), 96–106 teachers (≤ 18
lessons each), 55–62 rooms incl. the sports hall (`GYM`, 3 groups at once) and computer labs. The
generator plants a feasible timetable first (its soft score −7310…−7816 is the reference). Weights:
`GenerationWeights.DEFAULT` (windows 10, brokenDays 10, latePairs 3, preferences 5, homeRoom 1) +
discipline repeat 5. 90 s per run, surefire JVM (`-Xmx768m -XX:+UseSerialGC`), through the slot
wrapper on the shared laptop (16 threads) while other agents were building — the move evaluation
speed therefore varied between 16k and 47k moves/s, so compare runs with similar speed.

| Local search (`solverConfig.xml` variant) | Seed | CH (end, score) | First 0 hard | Score after 90 s | Moves/s |
|---|---|---|---|---|---|
| late acceptance 400, accepted 1 | 2026 | 1.5 s, −16hard | 10.9 s | 0hard/−1877soft | 16k |
| late acceptance 400, accepted 1 | 7 | 1.6 s, −7hard | 8.6 s | 0hard/−1610soft | 17k |
| late acceptance 400, accepted 1 | 99 | 2.5 s, −5hard | 8.4 s | 0hard/−1468soft | 20k |
| late acceptance 2000, accepted 1 | 2026 | 1.6 s, −16hard | 10.6 s | 0hard/−2570soft | 18k |
| tabu (entity ratio 0.02), accepted 1000 | 2026 | 1.6 s, −16hard | 28.2 s | 0hard/−917soft | 20k |
| simulated annealing 2hard/100soft, accepted 4 | 2026 | 2.8 s, −16hard | 28.7 s | 0hard/−867soft | 18k |
| late acceptance 400, accepted 4 | 2026 | 0.7 s, −16hard | 5.2 s | 0hard/−749soft | 40k |
| late acceptance 400, accepted 4 | 7 | 0.9 s, −7hard | 5.9 s | 0hard/−546soft | 32k |
| late acceptance 400, accepted 4 | 99 | 1.0 s, −5hard | 3.1 s | 0hard/−526soft | 35k |
| **late acceptance 400, accepted 16 (chosen)** | 2026 | 1.1 s, −16hard | **10.6 s** | **0hard/−479soft** | 32k |
| **late acceptance 400, accepted 16 (chosen)** | 7 | 0.8 s, −7hard | **4.7 s** | **0hard/−196soft** | 47k |
| **late acceptance 400, accepted 16 (chosen)** | 99 | 1.0 s, −5hard | **4.7 s** | **0hard/−245soft** | 46k |

Every run reached 0 hard far below the 90 s target (worst 28.7 s, chosen config ≤ 10.6 s). Breakdown
of the chosen config, seed 7 (hard constraints all 0 matches): group windows 1 (−20), teacher
windows 10 (−150), broken days 3 (−30), late pairs 111 (−483), discipline repeat 0, unwanted slots 2
(−15), not in own room 31 (−38), halves paired 54 of 56 possible (+540) → `0hard/−196soft`; the
per-constraint analysis of every run adds up to the solver's score. With one accepted move per step
the solver spends its steps on cheap room swaps (700+ lessons stayed outside their desired room);
16 accepted moves let it pick the best of several, which mostly fixes rooms and late pairs.
Reproduce with `-Dbenchmark=true -Dbenchmark.seed=<seed>` (and `-Dbenchmark.config=file:…` for a
variant).

### Real college snapshots (generator review, 15.11.2026)

`RealSnapshotGateTest` on the `GenerationRequested` events core sent on the stand (56 groups, 1077 units,
45 rooms, 101 teachers), completed the way this branch's core sends them (sibling keys, a daily limit of 4;
the runs below predate the repair and also had PE items with a derived GYM/SPORTS_GROUND type, which core no
longer sends). Production `solverConfig.xml`, request weights + the new
defaults, surefire JVM, laptop shared with other builds (moves/s are indicative).

| Snapshot (limit) | First progress point | First 0 hard | Final | Moves/s | Moved |
|---|---|---|---|---|---|
| `ki4-scope-3groups`, 53 free (30 s) | 0.45 s, 0 hard | 0.49 s | 0hard/−2583soft | 21k | 9 of 53 (old run: 34) |
| `course4-scope`, 168 free (60 s) | 1.0 s, 0 hard | 1.0 s | 0hard/−2324soft | 15k | 29 of 168 (old run: 109 of 171) |
| `college-from-published`, 1074 free (120 s) | 0.18 s, 0 hard | 0.20 s | 0hard/−1999soft | 11k | 142 of 1074 (old run 600 s: 603) |

Before B2 the same runs started at −4 / −18 / −120 hard. Whole college after 120 s (base = the published
version; teacher windows and waits are free pairs × kinds of week; one-pair, lone, overload and twice are
week occurrences; teacher days = teacher × weekday):

| Weights | Teacher windows | Shift waits | Broken days | Group 1-pair | Lone subgroup | Teacher 1-pair | Teacher days | 5+ pairs | Twice | Moved | Moves/s |
|---|---|---|---|---|---|---|---|---|---|---|---|
| base (published) | 117 | 191 | 36 | 5 | 2 | 100 | 380 | 39 | 12 | — | — |
| defaults | 31 | 344 | 20 | 1 | 6 | 68 | 372 | 17 | 50 | 142 | 11.0k |
| subgroupsTogether 10, disciplineTwice 3 | 33 | 383 | 22 | 0 | 4 | 69 | 373 | 17 | 17 | 140 | 10.6k |
| same + teacherShiftWait 2 | 41 | 221 | 29 | 1 | 2 | 70 | 371 | 27 | 32 | 130 | 11.5k |
| new rules 0, waits = windows (old model) | 33 | 38 | 18 | 8 | 46 | 327 | 442 | 66 | 67 | 377 | 14.7k |

The last row reproduces the old behaviour (the night run 4a: teacher gaps 309 → 57, teacher days 434,
one-pair teacher day-weeks 292) in 120 s, so the solver is not slower to converge; the new rules cost
about a quarter of the move speed and keep teacher days and subgroups as they are. With
`teacherShiftWait` 0 the solver turns in-shift windows into waits between shifts (191 → 344), which the
weight allows; whether the college minds is owner question Q2. Single 120 s runs: differences of a few
units are noise.

`college-from-scratch` (no base, 180 s, defaults): the construction heuristic ends at −133 hard, the
first 0 hard comes after 97 s (the night run never reached it in 600 s) and the run ends at
0hard/−2898soft with 40 lone subgroup slot-weeks and 179 «twice a day» occurrences, so a timetable from
scratch needs a longer limit than a re-generation from a base.

### Default weights (evaluation, 15.11.2026)

`SnapshotReplayTest` (§10) on the four real requests and five `ShapedCollege` shapes, production solver config and
termination, 4 JVMs in parallel on the laptop (16 threads), 2–4 runs (random seeds) per finalist. Reference =
the published timetable (all its lessons, from the `ki4-scope-3groups` request; the `college-from-published` base
misses 7 unmatched units). Counts per kind of week; «late» = group-day-weeks with a «4-та пара зміни»; «idle» =
teacher windows + waits between shifts (the old «teacher windows» of the genRun table). Whole college from the
published version, 150–180 s:

| Weights | Runs | Group win. | Broken | Late | Lone subgr. | Twice | Teacher win. | Waits | Idle | Teaching days | 1-pair teacher | 5+ pairs | PE outside | Moved |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| published | — | 0 | 34 | 291 | 2 | 12 | 117 | 189 | 306 | 380 | 99 | 41 | 0 | — |
| old solver (genRun 4a, 600 s) | 1 | 0 | 18 | — | 62 | — | — | — | 57 | 434 | 292 | 62 | 4 | 603 |
| first defaults (10,10,3,5,1,10,0,1,5,10,5,3,1) | 2 | 0–2 | 20–21 | 279–286 | 4–6 | 48–49 | 21–29 | 361–369 | 390 | 371–372 | 64–68 | 14–17 | 1–2 | 146–164 |
| + windows 30, wait 3, subgroups 10, twice 10, late 3 | 3 | 0–1 | 23–27 | 295–303 | 4 | 6–9 | 42–46 | 150–161 | 192–206 | 378–380 | 84–100 | 30–34 | 0–3 | 114–124 |
| **same, late 9 (chosen)** | 4 | **0** | 21–24 | 263–270 | 2–4 | 7–12 | 41–54 | 168–203 | 214–244 | 380–385 | 98–109 | 25–36 | 0 | 115–130 |
| chosen + teacherDays 7 | 2 | 0–1 | 24–30 | 268–274 | 2–4 | 7–10 | 47–63 | 193–215 | 240–278 | 379–381 | 85–89 | 27–29 | 0 | 104–142 |
| chosen + teacherDays 7, wait 2 | 2 | 0–1 | 24 | 263–270 | 2 | 10–11 | 45–50 | 264–272 | 314–317 | 375–377 | 80–81 | 24–29 | 0 | 114–139 |
| chosen + stability 2 | 2 | 0–1 | 21–26 | 265–267 | 2–4 | 9–11 | 61–64 | 182–201 | 243–265 | 381–382 | 103–105 | 28–30 | 0 | 88–94 |

The tight synthetic second shift (`tight-shift2`, from scratch, 120 s) decides between the close candidates: the
first defaults stay at −3 hard; late 3 reaches 0 hard in 2 of 3 runs; the chosen weights in 4 of 4 (10–20 s);
teacherDays 7 in 1 of 4; teacherDays 10 in 0 of 2. The scope runs (`ki4-scope-3groups`, `course4-scope`) stay at
the published values or better with every candidate. From scratch (`college-from-scratch`, 600 s) the chosen
weights reach 0 hard after 58–381 s (first defaults 57–72 s) and end with a discipline twice 58–67 times (first
defaults 149–157), late 302–313 (326–327) and idle 296–316 (623–655), but with more one-pair teacher days (115–119
vs 56–57): a timetable from scratch still needs a long limit and is not as good as the published one.

Why each change: windows 30 — the college has no student window, and at 10 a window was traded for teacher
compactness in 1–3 group-days per run; latePairs 9 — at 3 the stronger new rules pushed the 4th pair above the
published 291; teacherShiftWait 3 — at 0 the solver turned in-shift teacher windows into waits between shifts
(idle 306 → 390), at 3 a wait still costs a third of an in-shift window; subgroupsTogether 10 and disciplineTwice 10
— at 5 and 1 lone subgroup slots (2 → 4–6) and a discipline twice a day (12 → 49) grew past the published values,
and parallel subgroups are what makes a full second shift feasible. Known cost: teaching days 380 → 380–385 and
one-pair teacher day-weeks 99 → 98–109 on the whole-college run (stronger teacher-day weights broke the tight
shift). Full runs: `scratchpad/genfix/evaluation.md` of the genfix session.

**Repair (PE in the hall is soft).** The runs above kept PE in the hall with a hard derived room type (core B11)
and a range narrowed to the hall. After the repair (`specialRooms` 30, ordinary rooms back in the range, no derived
type), with the same defaults: `college-from-published` 120 s — group windows 0, broken 22, late 261, lone 2,
twice 4, teacher windows 45, waits 180, teaching days 381, one-pair teacher days 102, 5+ pairs 33, **PE outside 0**,
moved 131, room changed 53; `ki4-scope-3groups` 60 s — the same as before the repair (moved 5 of 53, PE outside 0).
With `specialRooms` 0 the same whole-college run is in the evaluation file (PE may then leave the hall).

### Owner's rules (15.11.2026)

`SnapshotReplayTest` again, production config and termination, the limits of the default-weights study (whole
college 150 s, `ki4` 60 s, `course4` 120 s, from scratch 600 s, synthetic 60–180 s), 3–4 JVMs on the laptop, seed 0
unless noted. «before» = develop before this change (soft subgroups, `latePairs` 9); «after» = the hard rule, the
sibling-aware construction order and room-aware sibling move, PE from the teacher's room, defaults with `latePairs` 0
and `subgroupsTogether` 30. New harness columns: `apart` = sibling copies not together (week occurrences; after the
change only one teacher's copies can be apart, those of two teachers are hard and every final score is 0 hard),
`lone` = lone subgroup slots of the scope's groups, `2-pair` = group day-weeks with two pairs, `spread` = per group and
week the most minus the fewest pairs over its weekdays, summed (balance).

| Dataset | Run | 0 hard, s | apart | lone | group win. | 1-pair | 2-pair | spread | twice | 4th | teacher days | 1-pair teacher | waits | PE outside | moved |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| whole college, published base | published | — | 0 | 2 | 1 | 5 | 75 | 178 | 12 | 289 | 380 | 100 | 191 | 0 | — |
| | before | 4.2 | 0 | 2 | 0 | 2 | 57 | 161 | 9 | 264 | 383 | 106 | 182 | 0 | 111 |
| | **after** | 3.4 | 0 | 2 | 0 | 2 | 86 | 186 | 12 | 337 | 374 | 89 | 139 | 0 | 120 |
| `ki4` (53 free) | before / **after** | 0.4 / 0.1 | 0 / 0 | 0 / 0 | 0 / 0 | 3 / 3 | 76 / 76 | 177 / 177 | 12 / 12 | 291 / 295 | 380 / 380 | 102 / 100 | 187 / 189 | 0 / 0 | 5 / 4 |
| `course4` (168 free) | before / **after** | 0.2 / 0.1 | 0 / 0 | 0 / 0 | 0 / 0 | 3 / 3 | 68 / 82 | 168 / 179 | 7 / 8 | 282 / 305 | 379 / 383 | 97 / 104 | 194 / 180 | 0 / 0 | 28 / 30 |
| from scratch | before | 438 | 39 | 28 | 2 | 0 | 76 | 178 | 60 | 314 | 385 | 128 | 290 | 10 | — |
| | **after** | 66 | 0 | 2 | 2 | 0 | 85 | 195 | 57 | 376 | 374 | 90 | 251 | 0 | — |
| `real-shaped` | before / **after** | 41 / 23 | 74 / 24 | 16 / 0 | 0 / 1 | 0 / 0 | 80 / 77 | 176 / 211 | 14 / 22 | 296 / 384 | 394 / 379 | 154 / 121 | 320 / 273 | 0 / 0 | — |
| `tight-shift2` | before / **after** | 13.5 / 14.2 | 20 / 0 | 0 / 0 | 0 / 0 | 0 / 0 | 10 / 22 | 52 / 58 | 10 / 8 | 120 / 154 | 158 / 156 | 62 / 42 | 139 / 106 | 0 / 0 | — |
| `many-splits` | before / **after** | 0.6 / 1.7 | 90 / 16 | 4 / 0 | 0 / 0 | 0 / 0 | 20 / 38 | 63 / 83 | 24 / 24 | 97 / 158 | 213 / 203 | 106 / 67 | 102 / 59 | 0 / 0 | — |
| `teacher-limits` | before / **after** | 17.8 / 4.0 | 48 / 14 | 20 / 4 | 1 / 3 | 1 / 0 | 13 / 26 | 61 / 70 | 6 / 14 | 132 / 171 | 198 / 188 | 101 / 65 | 134 / 61 | 0 / 0 | — |
| `small-stream` | before / **after** | 0.1 / 0.1 | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 | 6 / 8 | 16 / 24 | 0 / 0 | 18 / 36 | 55 / 55 | 28 / 36 | 47 / 32 | 0 / 0 | — |

Reading. The published version keeps the hard rule in all 72 sibling pairs, so runs from it start and stay at 0 hard
(the first progress point, 0.1–3.4 s). The only lone subgroup slot left in the real college is 1ОП1 English on
Wednesday pair 1 (2 slot-weeks), a subgroup-1 lesson whose load has no subgroup-2 counterpart (DOMAIN §13 Q4); the
scope runs have none. From scratch the run reaches 0 hard after 66 s instead of 438 s, with 2 lone slot-weeks instead of
28 and no PE lesson in a classroom (the teacher's room marks PE now, instead of 10 units in classrooms). Without the
4th-pair penalty the 4th pair is used more (337 vs 289 published) and the days are a little less even than published
(spread 186 vs 178 from the published version, 195 from scratch; scope runs 177–179 as published): one-pair days stay
at 0–2, a discipline twice a day at the published 12, broken days below the published 36, but two-pair days grow
(86 vs 75). Teacher metrics improve (teaching days 380 → 374, one-pair teacher days 100 → 89, waits 191 → 139). The
synthetic colleges reach 0 hard in every run, with up to 3 group windows (`teacher-limits`; before 0–1) and 14–24
week occurrences of one teacher's copies not on neighbouring pairs (soft); real data has no such copies.

How the parts were chosen (single runs unless noted; all in `scratchpad/owner-rules/candidates.md` of the session):
- the hard rule for one teacher's copies too (neighbouring pairs) left `tight-shift2` at −1 hard in 3 of 3 seeds; soft at
  30 reached 0 hard in all 13 synthetic runs (8 candidate runs over 4 shapes and 2 seeds, and the final 5);
- the old construction order never brought `real-shaped` to 0 hard in 180 s (5 runs, −2…−6); placing the copies of a
  key one after another did in 22–153 s (7 runs);
- the room-aware sibling move, same order: `many-splits` −2 → 0 hard after 6.5 s, `real-shaped` 48 → 22 s; with the old
  order it did not rescue `teacher-limits` (1 run at −1), so it is kept as a help, not as the fix;
- `studentOnePairDay` 30 instead of 10: one-pair days 1 → 0 on the whole college, but from scratch the spread went 195
  → 225 and a discipline twice 44 → 66; kept at 10;
- `latePairs` 0 alone (old soft subgroups, `real-shaped`) raised the spread 176 → 192 and the two-pair days 80 → 94;
  the hard rule alone with `latePairs` 9 left the run at −4 hard, so the windows and infeasibility came from the rule,
  not from the 4th pair.

## 12. Decisions (short)

1. **One entity class with per-unit value ranges** instead of separate WHOLE/HALF classes: one set of
   constraints, parity range per unit, and the ranges also encode the shift window, teacher
   unavailability, room type and capacity (they shrink the search space; the hard constraints still
   check pins and fallbacks).
2. **Room warm start** + construction heuristic over timeslot × parity: CH for 896 units takes about a
   second; a cartesian product with ~45 rooms would take tens of seconds.
3. **Penalties in week occurrences** (BOTH = 2, half = 1), so a numerator/denominator pair never
   conflicts and a half-week clash weighs half.
4. **Broken day on the group-day** (union of cohorts, as DOMAIN §5 defines it), **windows per cohort**
   (what a student of a subgroup actually waits); graded broken-day penalty for a better gradient.
5. **Late pair** = 4th pair of the shift window or later, by the one definition in `contracts.LatePairs`
   (shared with the core metric and the web; title «4-та пара зміни»); the 3-pair Friday window of the
   second shift has none. The owner confirmed it is a normal pair (15.11.2026): the weight defaults to 0 and
   the rule stays for a dispatcher who wants it.
6. **Halves bonus** only for the same cohort (group + subgroup) and different parities; weight =
   `brokenDays` because it serves the same goal.
7. **Discipline repeat weight** from configuration (the request has no field for it), default 5.
8. **Special-purpose rooms** (sports hall/ground) only for units that require or name them, stood there in the
   base, or whose item keeps to them (`baseRoomTypes`, from the base or, from scratch, the teacher's room).
9. **Own score analysis** (Timefold Community has no ScoreAnalysis); cross-checked against the solver.
10. **Strict snapshot validation**: a broken snapshot fails fast with every problem listed in Ukrainian;
    desired rooms are preferences and an unknown one is only dropped (audit 12.11.2026).
11. **Outbox-lite for results**: payload stored with the terminal status, sent with a per-message
    confirm, re-sent with the same event id until confirmed.
12. **CANCELLED keeps the best timetable** found so far, so «stop and apply» is possible.
13. **New rules are soft and weighted** (generator review, Nov 2026): every default comes from the real
    timetable's statistics and `0` switches a rule off — except what the owner made a college rule on
    15.11.2026 (decision 22).
14. **One grouping per day shape**: the group-day rules (windows, broken day, one-pair day, lone
    subgroup), the teacher-day rules (windows, shift wait, one-pair day, overload) and the discipline
    rules (twice, three times) each read one shared incremental collector, so the solver did not get a
    pass over the units per new rule.
15. **Group windows take the worst cohort** per kind of week (B10, like the core metric): a whole-group
    gap of a split group is one window, not one per subgroup.
16. **A wait between shifts is not a teacher window**: a gap whose neighbouring lessons are with groups
    of different shifts gets its own weight (default 0), the same definition as core's metric.
17. **Stability is measured against `initial`**: a move costs per week occurrence, a room change only
    when the slot was kept and the base had a room.
18. **Teacher-day baseline** = the base days, at least the days the load needs at the daily limit, so a
    teacher with new load items is not penalized for the days they need.
19. **A discipline twice a day is judged per student** (busiest cohort), so a teacher's two subgroups one
    after the other — what «Subgroups apart» asks for — are not «twice».
20. **Sibling move** instead of a «follower» entity: the model keeps one entity class; the custom move
    keeps subgroups together while they move, and the hard «Subgroups not together» keeps them together
    otherwise.
21. **Warm-start rooms are free at the start slot** (B2): a run never starts with a room clash the base
    did not have.
22. **Subgroups of a split discipline at the same time is hard** for copies of two teachers (owner, 15.11.2026);
    one teacher's copies on neighbouring pairs is soft with the strongest default, because hard it made a full
    second shift infeasible. Only units with a sibling key are scored and pinned units have none, so lessons pinned
    outside the scope cannot make a run infeasible. A group's one-pair day and PE in the hall stay soft (the hall's 5
    places), and the web shows these rules as fixed, not as priorities.
23. **Sibling copies are placed one after another** by the construction heuristic and **move with free rooms**:
    with a hard rule the copies cannot be fixed one at a time.

## 13. Known gaps

* Streams (one lesson for several groups) are not in the snapshot and not modelled.
* One scheduler instance: restart recovery fails every job received by an earlier process.
* Completions are not published as `mandatory`: if core's queue `core.q.scheduler-results` were
  deleted, the broker would confirm and drop them. Core declares that queue at startup and can only
  request a generation while running, so in practice the queue exists before any result is sent.
* `generation.progress` is best effort: a progress point may arrive after `generation.completed` of
  the same job (different threads); core should ignore progress of a finished job.
* Teacher preferences are only UNAVAILABLE (hard) and UNWANTED (soft) slots from the snapshot. The daily
  pair limit and the teaching-day baseline are soft rules; a methodical day is not modelled (no evidence
  the college needs one, DOMAIN open questions).
* The sibling move covers subgroup copies of one occurrence only. Crossing two split disciplines (sub 1 of A
  with sub 2 of B, then swapped) breaks the hard rule for copies of two teachers; with one teacher per discipline it
  is what «Subgroups apart» asks for, and the synthetic colleges still end with some of those pairs apart (soft).
* A free subgroup unit whose sibling is pinned (the dispatcher pinned one subgroup's lesson in the scope) has no
  key (core sends keys on free units only), so the hard rule does not tie it to the pinned copy; only «Subgroup
  alone» pulls it there.
* A subgroup lesson without a counterpart in the load (1ОП1 English, Wednesday pair 1; DOMAIN §13 Q4) cannot be
  paired by any timetable: it stays one lone slot in both weeks.
* Stability counts every move from the base the same, whatever the reason; the dispatcher sets
  `stability` 0 to rebuild freely.
* **Online lessons that are not pinned** (owner question): a free unit comes from a load item, which has
  no online flag, so a regenerated lesson is placed in a room and comes back in person even when the
  base lesson it replaces was marked online. Pinned lessons keep the flag. Carrying the flag of the
  matched base lesson to a free unit would need a rule for where such a unit stands (no room at all?),
  which DOMAIN §5 does not give; the dispatcher marks it online again after generation.
