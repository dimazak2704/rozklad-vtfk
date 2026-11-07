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
| `solver.score` | `TimetableConstraintProvider`, `TimetableConstraint` (ASCII id + Ukrainian title), incremental collectors (`DayCollectors`, `LaneCounts`, `WeekBalance`), `ConstraintWeights`, `TimetableAnalyzer` |
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
    units that require them or name them as preferred/home room; if nothing fits, every room (reported
    by the hard constraint);
  * parity: `BOTH` for WHOLE units, `NUMERATOR`/`DENOMINATOR` for HALF units.
  Timefold 2.8 validates assigned values against these ranges, so swaps that would break a range are
  never made.
* **Pinned units** (`pinned != null`) are `@PlanningPin` with the pinned slot, parity and room; a pin
  without a room uses the placeholder `Room.NONE` (occupies nothing) and comes back with `roomId: null`.
* **Student cohorts**: a group without subgroups is one cohort; a split group has cohorts = its
  subgroups, and a whole-group lesson belongs to all of them. Up to 8 subgroups per group.
* **Week arithmetic**: BOTH meets in both week types, a half in one. Penalties count week
  occurrences (two BOTH lessons in one slot = 2, BOTH vs a half = 1, numerator vs denominator = 0).
* **Room warm start**: before solving, every unit gets its desired room (preferred room of the load
  item, else the group's home room, if compatible) or the least loaded compatible room. The
  construction heuristic then places only timeslot × parity (≈20–40 moves per unit instead of
  ≈2000 with rooms in the cartesian product); local search changes rooms freely.

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
| Group windows | Вікна в розкладі групи | soft | group-day: free pairs between lessons per cohort and week type | `windows` |
| Teacher windows | Вікна у викладача | soft | teacher-day: free pairs between lessons per week type | `windows` |
| Broken day | Рваний день (чисельник і знаменник різні) | soft | group-day: \|Δ pair count\| + 1 if the first pair differs | `brokenDays` |
| Late pair | Пізні пари в межах зміни | soft | unit on the 4th pair of its shift costs 1, the 5th 2, …, × week occurrences | `latePairs` |
| Discipline repeated | Дисципліна три й більше разів на день | soft | group-day-discipline: distinct pairs above 2 per week type | config (5) |
| Unwanted slot | Небажаний для викладача час | soft | unit, week occurrences | `preferences` |
| Room preference | Заняття не у своїй аудиторії | soft | unit outside its desired room, week occurrences | `homeRoom` |
| Halves paired | Чисельник і знаменник в одному слоті (бонус) | soft **reward** | two halves of one cohort in one slot with different parities | `brokenDays` |

Weights: `GenerationWeights.DEFAULT` = windows 10, brokenDays 10, latePairs 3, preferences 5,
homeRoom 1; `0` switches a constraint off (`ConstraintWeightOverrides`). Hard constraints are not
weighted. Windows, broken days and discipline repeats use custom incremental collectors over
«lanes» (cohort × week type) — `TimetableSolverTest` runs the solver in `FULL_ASSERT` to prove the
incremental score equals a full recalculation.

**Score analysis.** `SolutionManager.analyze` (ScoreAnalysis) is a commercial feature in Timefold 2.8
and throws in the Community edition. `TimetableAnalyzer` therefore computes matches and scores per
constraint with the same domain rules and the same match granularity; `TimetableAnalyzerTest`
checks, on a planted and on a deliberately broken timetable (every hard constraint violated), that
each constraint's impact equals the solver's (`ConstraintVerifier`) and that the sum equals the
solver's score. The analysis always lists all 15 constraints (hard first); a switched-off
constraint shows its matches with score `0hard/0soft`.

## 4. Solver configuration (`src/main/resources/solverConfig.xml`)

* Construction heuristic: `FIRST_FIT_DECREASING` over the cartesian product timeslot × parity (rooms
  are warm-started). Difficulty (`LessonUnitDifficulty`): WHOLE before HALF (halves then pair up in
  the remaining slots), scarce rooms first (sports hall, labs), then busy teachers, then units with
  fewer allowed timeslots.
* Local search: late acceptance (400) with union of change (timeslot, room, parity) and swap
  (timeslot, room) moves, `acceptedCountLimit` 16 (chosen by the benchmark in §11).
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
| `units` | load items split by DOMAIN §4 (whole pairs + at most one HALF), one unit per subgroup for split items with that subgroup's teacher; `requiredRoomType`/`preferredRoomId` from the load item, `homeRoomId` from the group |
| `units[].pinned` | pinned template lessons of `baseVersionId`, and lessons of groups outside the scope that must stay as they are |

## 9. Contract notes (no change to `libs/contracts`)

* `analysis[].constraint` is the Ukrainian title; ids stay internal. `analysis[].score` is
  `HardSoftScore.toString()` (`-2hard/-30soft`), weights included.
* CANCELLED may carry assignments (best timetable before the cancel) — core may offer «apply anyway».
* `hardScore`/`softScore` are 0 for FAILED and for CANCELLED without assignments.
* Pinned units without a room come back with `roomId: null`; units of other groups that core wants
  respected (out of scope of a partial generation) should be sent as pinned units.
* `ProblemSnapshot` has no stream id, so «one lesson for several groups» is not modelled: two units of
  a stream would be treated as a teacher conflict.
* Snapshot validation is strict: unknown group/teacher/room references, subgroups the group does not
  have, a shift without windows, pins outside the timeslots, duplicate ids, no rooms → FAILED with the
  list of problems.

## 10. How to test

```bash
source scripts/env.sh
mvn -q -pl services/scheduler -am verify                               # unit + Testcontainers ITs
mvn -q -pl services/scheduler -am test -Dtest='TimetableConstraintProviderTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -q -pl services/scheduler -am test -Dtest=RealSizeBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dbenchmark=true [-Dbenchmark.seconds=90] [-Dbenchmark.config=file:/path/to/solverConfig.xml]
```

* `TimetableConstraintProviderTest` — ConstraintVerifier for every constraint (positive and negative
  cases, parity overlap, subgroups, `maxParallel`, pins without a room) and request weights.
* `TimetableAnalyzerTest` — analysis = solver, constraint by constraint.
* `TimetableProblemFactoryTest` — value ranges, warm start, pins, weights, Ukrainian validation errors.
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
  first, so every generated problem is known to have a 0-hard solution.

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
`GET /api/scheduler/jobs/{id}` needs an ADMIN token from Keycloak. Done once for this module: two
groups / 17 units, 8 s limit → one `generation.progress`, `generation.completed` SOLVED `0hard/4soft`
with 17 assignments and 15 analysis rows, REST 200 with `resultDelivered: true`; the database, role
and vhost were dropped afterwards.

## 11. Benchmark (real college size)

`RealSizeBenchmarkTest` on `SyntheticCollege.realSize()`: 56 groups (41 first shift, 15 second
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
5. **Late pair** = 4th pair of the shift or later; the 3-pair Friday window of the second shift has none.
6. **Halves bonus** only for the same cohort (group + subgroup) and different parities; weight =
   `brokenDays` because it serves the same goal.
7. **Discipline repeat weight** from configuration (the request has no field for it), default 5.
8. **Special-purpose rooms** (sports hall/ground) only for units that require or name them.
9. **Own score analysis** (Timefold Community has no ScoreAnalysis); cross-checked against the solver.
10. **Strict snapshot validation**: a broken snapshot fails fast with every problem listed in Ukrainian.
11. **Outbox-lite for results**: payload stored with the terminal status, sent with a per-message
    confirm, re-sent with the same event id until confirmed.
12. **CANCELLED keeps the best timetable** found so far, so «stop and apply» is possible.

## 13. Known gaps

* Streams (one lesson for several groups) are not in the snapshot and not modelled.
* One scheduler instance: restart recovery fails every job received by an earlier process.
* Completions are not published as `mandatory`: if core's queue `core.q.scheduler-results` were
  deleted, the broker would confirm and drop them. Core declares that queue at startup and can only
  request a generation while running, so in practice the queue exists before any result is sent.
* `generation.progress` is best effort: a progress point may arrive after `generation.completed` of
  the same job (different threads); core should ignore progress of a finished job.
* Teacher preferences are only UNAVAILABLE (hard) and UNWANTED (soft) slots from the snapshot; daily
  load limits of teachers (> 4 pairs a day) are not modelled.
