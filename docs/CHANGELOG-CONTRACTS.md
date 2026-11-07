# Contract deviations and clarifications

Every agent appends its own section (ARCHITECTURE §14): what differs from `CONTRACTS.md` /
`DOMAIN.md` and why. Additive clarifications are listed too, so clients know the exact shape.

## Foundation (events, errors, schema)

| Area | Decision | Why |
|---|---|---|
| Event JSON | Envelope fields `eventId`, `occurredAt`, `version` are top-level fields of every event record (no nested envelope object). | Matches "every payload has eventId, occurredAt, version" and keeps consumers simple. |
| `order.published` | `{eventId, occurredAt, version, order: OrderPayload}` — the order is nested under `order`. | `OrderPayload` is reused unchanged as the body of `POST /api/documents/orders/render`. |
| `generation.cancel` | Java record is `GenerationCancelRequested` (routing key unchanged). | A record name should state what happened. |
| `schedule.changed.reason` | Enum `ScheduleChangeReason {VERSION_PUBLISHED, ORDER_PUBLISHED, CALENDAR_CHANGED, LESSONS_EDITED}`. | The contract gave no type; an enum is checked at compile time. Append values only. |
| `ProblemSnapshot.units[].unitId` | Opaque `String`, unique within one snapshot (not a UUID). | Units are derived (load item × subgroup × index), not stored entities. |
| Scores | `hardScore`/`softScore` are `long`; `analysis[].score` is the solver's formatted score string (`0hard/-35soft`). | Covers int and long score types of Timefold; the string is for display only. |
| `GenerationRequested.weights` | `null` is replaced by `GenerationWeights.DEFAULT` (10, 10, 3, 5, 1). | `weights?` is optional in the REST request. |
| `absence.reported.pairs` | `null` = whole day (kept as `null` in JSON). Reason category and comment are never in the event. | DOMAIN §7: absence reasons are private. |
| Times in JSON | `LocalTime` is written as `HH:mm` by services; events between services use the same mapper. Reading accepts `HH:mm` and `HH:mm:ss`. | CONTRACTS: times `HH:mm`. |
| `Conflict.kind` | Adds soft kinds `WINDOW, BROKEN_DAY, DAY_OVERLOAD, DISCIPLINE_REPEAT, UNWANTED` to the hard kinds. Null `date`/`weekday` are omitted. | `GET …/conflicts` returns `soft[]` (DOMAIN §5) that need kinds too. |
| ProblemDetail | `type` = `urn:rozklad:problem:<slug>` (`validation`, `not-found`, `conflict`, `schedule-conflict`, `stale-data`, `data-integrity`, `business-rule`, `unauthorized`, `forbidden`, `internal`, gateway: `service-unavailable`, `gateway-timeout`); extra members `instance`, `requestId`; 422 has `code`. | Stable machine-readable types; `requestId` links a user report to logs. |
| Validation `errors[]` | Sorted by `field`; nested fields use dots/indexes (`items[0].name`); request parameters use the parameter name. | Deterministic output for the UI. |
| Lesson uniqueness | DOMAIN's partial unique indexes are implemented as deferrable `EXCLUDE USING btree … WHERE (status = 'SCHEDULED' …)` constraints with the same names; checked at commit or via `LessonRepository.checkSlotConstraintsNow()`. | Same guarantee, but applying a change / SWAP inside one transaction works despite Hibernate's INSERT-before-UPDATE flush order. |
| Extra DB guards | One active semester; one PUBLISHED version per semester; one main ORDER per semester and date; ORDER ⇔ no parent; PUBLISHED ⇒ number; lessons Mon–Fri; parity overrides on Mondays; course 1..6; shift 1/2; pair 1..8; weekday 1..5. | Invariants of DOMAIN made explicit in the schema. |
| Nullable columns | `room.capacity`, `student_group.size`, `student_group.number`, `specialty.code`, `specialty.department_id`, `teacher.first_name`, `teacher.middle_name`. | Imported data (Excel) does not have these values; DOMAIN forbids inventing them. |
| Child tables with `id` | `teacher_discipline`, `calendar_period_group`, `load_item_teacher` keep an `id uuid` PK plus a unique natural key. | DOMAIN §3: "All tables: id uuid pk". |
| notification path | `POST /api/notify/telegram/webhook` is reserved and open (secret header checked by the controller). | Needed for WEBHOOK mode (ARCHITECTURE §6); not listed in CONTRACTS. |
| Gateway | Adds no `X-Forwarded-*` headers (no trusted proxy); preserves `Host`; own errors are ProblemDetail. | Forgery-safe defaults; correct absolute `Location` headers. |
