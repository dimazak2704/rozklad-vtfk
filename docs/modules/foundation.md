# Foundation — build, infrastructure, schema and shared conventions

What exists after the foundation step and the rules every module agent builds on. Specs stay in
`docs/ARCHITECTURE.md`, `DOMAIN.md`, `CONTRACTS.md`, `UI.md`; deviations are listed in
`docs/CHANGELOG-CONTRACTS.md`.

## 1. Layout

```
pom.xml                         rozklad-parent: Boot 4.1.2 parent, BOMs, plugin management, modules
.mvn/maven.config               named file locks (safe parallel Maven runs on one ~/.m2)
.mvn/jvm.config                 -Dfile.encoding=UTF-8
libs/contracts                  rozklad-contracts: event records + shared enums (plain Java, no Spring)
services/core                   rozklad-core      :8081  Spring Modulith, full schema, entities, shared kernel
services/scheduler              rozklad-scheduler :8082  skeleton (Timefold starter, queue, security)
services/importer               rozklad-importer  :8083  skeleton (POI, PDFBox, Anthropic SDK, RestClient→core)
services/notification           rozklad-notification :8084 skeleton (Telegram libs, POI/PDFBox, queue, RestClients)
services/gateway                rozklad-gateway   :8080  complete (routes, CORS, rate limit, request id, errors)
infra/docker-compose.yml        profiles infra / app
infra/postgres/init.sql         databases + users per service
infra/keycloak/rozklad-realm.json
scripts/env.sh                  JDK 25 + Maven 3.9.11 (source it in every shell)
scripts/run-local.sh            run one service jar on the host with secrets from .env
.env.example                    every non-secret dev default, empty secrets
```

Each service has the same small skeleton (copied, not shared, so every agent owns its service):
`config/SecurityConfig` (path rules), `KeycloakRealmRoleConverter`, `KeycloakProperties`,
`ProblemDetailSecurityHandlers`, `InfrastructureConfig` (Clock, JSON conventions, Jackson 3 for
Hibernate JSON, uk locale, OpenAPI), `web/ApiExceptionHandler`, `RequestIdFilter`,
`NotFoundException`, `BusinessRuleException`, `messages.properties` (Ukrainian MVC error titles),
`application.yml`, `Dockerfile`, `.dockerignore`, `*ApplicationIT` smoke test.

## 2. Build and run (Git Bash)

```bash
source scripts/env.sh                                   # default java is 8!
mvn -q -DskipTests install                              # whole reactor, jars in services/*/target/app.jar
mvn -q verify                                           # unit (*Test, surefire) + Docker ITs (*IT, failsafe)
mvn -q -pl services/core -am verify                     # one module with its dependencies
mvn -q -pl services/core -am verify -Dit.test=PersistenceMappingIT -Dtest='None*' \
    -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false   # a single IT
```

Parallel builds of the same checkout: give every build its own output directory. The value is
interpolated per module, so quote it to keep `${...}` away from the shell:

```bash
mvn -q -pl services/core -am verify -Dbuild.dir='${project.basedir}/target-catalog'
```

(`target-*/` is git-ignored. Docker images always copy `target/app.jar`.) `.mvn/maven.config` sets
`aether.syncContext.named.factory=file-lock` and `nameMapper=file-gav` (verified against resolver
1.9.24 of Maven 3.9.11), so concurrent Maven processes can share `~/.m2`.

Infrastructure and apps:

```bash
docker compose -f infra/docker-compose.yml --profile infra up -d           # postgres, rabbitmq, redis, keycloak
mvn -q -DskipTests package                                                 # jars for the images
docker compose -f infra/docker-compose.yml --profile app up -d --build     # + all services and web
scripts/run-local.sh core                                                  # or run one service on the host
```

Infra services carry both profiles, so `--profile app` alone also starts them. Keycloak needs about
2 minutes on the very first start (augmentation + schema). `--import-realm` skips an existing realm:
after editing `rozklad-realm.json` delete the realm in the admin console (or `docker compose ... down -v`,
which also wipes all databases).

Spring Boot does not read `.env`. On the host use `scripts/run-local.sh <service>` (exports non-empty
`.env` values, tolerates CRLF) — tests never see the real secrets.

## 3. Ports

| What | Host | In compose network |
|---|---|---|
| gateway | 8080 | gateway:8080 |
| core / scheduler / importer / notification | 8081 / 8082 / 8083 / 8084 | core:8081 … |
| web (nginx, written by the web agent) | 8088 → container `${WEB_CONTAINER_PORT:-80}` | web |
| Vite dev server | 5180 | — |
| PostgreSQL 18 | **5433** (5432 is a local PostgreSQL) | postgres:5432 |
| RabbitMQ / management UI | 5672 / 15672 (rozklad / rozklad) | rabbitmq:5672 |
| Redis | 6379 | redis:6379 |
| Keycloak | 8180 (admin console: admin / admin, master realm) | keycloak:8080, health on 9000 |

Databases (owner = user = password): `core`, `scheduler`, `importer`, `notification`, `keycloak`;
each user can connect only to its own database. Superuser `postgres` / `postgres`.

## 4. Keycloak realm `rozklad`

Issuer is always `http://localhost:8180/realms/rozklad` (`KC_HOSTNAME`); containers fetch keys from
`http://keycloak:8080/realms/rozklad/protocol/openid-connect/certs` (`KEYCLOAK_JWKS_URI`). Login
pages are Ukrainian by default (`uk`, `en` supported). Roles: `ADMIN`, `TEACHER`, `SERVICE`.

| User | Password | Role | Fixed id (`sub`) |
|---|---|---|---|
| admin | admin | ADMIN | `a0000000-0000-4000-8000-000000000001` |
| krentsin (Кренцін М. Д.) | teacher | TEACHER | `a0000000-0000-4000-8000-000000000002` |
| nepyivoda (Непийвода М. В.) | teacher | TEACHER | `a0000000-0000-4000-8000-000000000003` |
| service-account-rozklad-core-admin | — | realm-management manage/view/query-users | `a0000000-0000-4000-8000-000000000010` |
| service-account-rozklad-notification | — | SERVICE | `a0000000-0000-4000-8000-000000000011` |
| service-account-rozklad-importer | — | SERVICE | `a0000000-0000-4000-8000-000000000012` |

The core seed links teachers «Кренцін» and «Непийвода» by `teacher.keycloak_user_id` = the ids above.

| Client | Type | Secret (dev, also in `.env.example`) |
|---|---|---|
| rozklad-web | public, Authorization Code + PKCE S256, redirects `http://localhost:5180/*`, `http://localhost:8088/*` | — |
| rozklad-core-admin | confidential, service account | `dev-core-admin-secret` (`CORE_ADMIN_CLIENT_SECRET`) |
| rozklad-notification | confidential, service account (SERVICE) | `dev-notification-secret` |
| rozklad-importer | confidential, service account (SERVICE) | `dev-importer-secret` |
| rozklad-dev-cli | public, direct access grants (scripts/tests only) | — |

Tokens for scripted tests:

```bash
TOKEN=$(curl -s -d client_id=rozklad-dev-cli -d grant_type=password -d username=admin -d password=admin \
  http://localhost:8180/realms/rozklad/protocol/openid-connect/token | python -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/me
# service account token (client credentials):
curl -s -d client_id=rozklad-notification -d client_secret=dev-notification-secret -d grant_type=client_credentials \
  http://localhost:8180/realms/rozklad/protocol/openid-connect/token
```

Access tokens live 15 min; `realm_access.roles`, `preferred_username` and `sub` are present (the
realm JSON does not define client scopes, so Keycloak creates the default ones incl. `basic`).

## 5. Security in the services

* Every service is a stateless resource server with a custom `JwtDecoder`: JWKS from
  `KEYCLOAK_JWKS_URI`, issuer validated against `KEYCLOAK_ISSUER` (no discovery call, keys are fetched
  lazily, so services start without Keycloak). `realm_access.roles` → `ROLE_<NAME>`.
* Deny by default (`anyRequest().denyAll()`); `ASYNC`/`ERROR` dispatches are permitted (SSE, error
  pages). Health/info and OpenAPI are open (`API_DOCS_ENABLED=false` hides the docs).
* core: `/api/public/**` open; `/api/admin/import/**` ADMIN or SERVICE; `/api/admin/**` ADMIN;
  `/api/me`, `/api/me/**` TEACHER or ADMIN; `/api/internal/**` SERVICE; `/api/events/stream` ADMIN or
  TEACHER; other actuator endpoints ADMIN.
* importer: `POST /api/import/ai/parse-absence` ADMIN or SERVICE, other `/api/import/**` ADMIN.
* notification: `GET /api/documents/orders/*` open (serve only PUBLISHED orders!),
  `POST /api/documents/orders/render` ADMIN, `POST /api/notify/bindings/codes` ADMIN or TEACHER
  (teacher only for himself), `POST /api/notify/telegram/webhook` open (check the
  `X-Telegram-Bot-Api-Secret-Token` header), other `/api/notify/**` ADMIN.
* scheduler: `/api/scheduler/**` ADMIN.
* Ownership (a teacher sees/changes only his own data) is checked in services, not by URL rules.
* core: declare a `CurrentUser` controller parameter (id = Keycloak `sub`, username, roles) or inject
  `CurrentUserProvider`. Actor columns (`created_by`, `published_by`, …) store the username.
* Outgoing service calls (importer, notification): inject `RestClient coreRestClient`
  (notification also `importerRestClient`). They add a client-credentials token of the service
  account (registration `rozklad-service`, token URI `${KEYCLOAK_URL}/realms/rozklad/...`) and
  forward `X-Request-Id`.
* CORS is handled only by the gateway (`CORS_ALLOWED_ORIGINS`); services never add CORS headers.

## 6. Errors (RFC 9457)

Throw, don't build responses: `NotFoundException.of("Групу", id)` → 404,
`ConflictException.schedule(List<ConflictDto>)` → 409 with `conflicts[]`, `new ConflictException(msg)`
→ plain 409, `BusinessRuleException(code, msg)` → 422 with `code`; Bean Validation → 400 with
`errors[{field,message}]` (Hibernate Validator's Ukrainian messages, locale fixed to `uk`);
`OptimisticLockingFailureException` → 409 «Дані змінилися»; `DataIntegrityViolationException` →
409 «Конфлікт даних» (SQL details only in the log); auth → 401 (with `WWW-Authenticate: Bearer`) /
403; anything else → 500 without details. Every problem has `type` `urn:rozklad:problem:<slug>`,
Ukrainian `title`/`detail`, `instance` and `requestId`. For other statuses (e.g. 410 for an expired
invite) throw Spring's `ErrorResponseException`/`ResponseStatusException` with a Ukrainian detail.
`ConflictDto(kind, severity, message, lessonIds, date?, weekday?, pair)`; `ConflictKind` also has the
soft kinds `WINDOW, BROKEN_DAY, DAY_OVERLOAD, DISCIPLINE_REPEAT, UNWANTED`.

## 7. core — modules, entities, persistence conventions

Modules (Spring Modulith, `ModularityTests` verifies them and writes PlantUML docs to
`target/spring-modulith-docs`): `shared` (OPEN), `catalog`, `calendar`, `load`, `timetable`,
`substitution`, `order`, `accounting` (no tables), `identity`, `settings`.

* **Entities and repositories live in the module base package** (readable by other modules);
  services, controllers and DTOs go to sub-packages (`catalog.internal…`, `catalog.web…`).
  `ArchitectureRulesTest` enforces this, forbids Jackson 2 databind, `LocalDate.now()`-style calls
  without a `Clock`, field injection, `System.out` and java.util.logging.
* **References between aggregates/modules are plain `UUID` columns** (`teacherId`, `groupId`, …), never
  JPA associations — no lazy-loading traps, no module cycles. Use entity joins in JPQL if needed:
  `select l from Lesson l join Teacher t on t.id = l.teacherId`. JPA associations exist only inside an
  aggregate: `BellProfile→BellSlot`, `CalendarPeriod→CalendarPeriodGroup`, `LoadItem→LoadItemTeacher`,
  `OrderDocument→OrderItem` (cascade ALL + orphan removal, children created through the parent).
* `IdentifiableEntity`: UUID v7 (`Ids.newId()`) assigned at construction, `equals/hashCode` by id,
  implements `Persistable` so `save()` persists new objects (no extra SELECT from `merge`).
  `BaseEntity` adds `@Version long version`, `createdAt`, `updatedAt` (Spring Data auditing from the
  `Clock` bean, `JpaConfig`). Mutable aggregates extend `BaseEntity`; link/child rows (aliases,
  competences, slots, history, preferences, order items, period groups, load teachers, parity
  overrides, shift windows) extend `IdentifiableEntity`. `Setting` uses its `key` as id.
* Enums are `@Enumerated(STRING)` with CHECK constraints listing the values. Shared enums come from
  `ua.vtc.rozklad.contracts` (`Parity`, `LessonKind`, `RoomType`, `ChangeKind`, `ChangeStatus`,
  `AbsenceSource`, `AbsenceReasonCategory`, `OrderKind`, `OrderStatus`, `OrderRowType`,
  `GenerationStatus`, `UnitWeight`, `ConfirmationDecision`); module enums live with their entities.
  Adding an enum value = new migration that replaces the CHECK constraint.
* JSON columns (`jsonb`): `JsonNode` (Jackson 3) or `List<String>` with `@JdbcTypeCode(SqlTypes.JSON)`.
  Hibernate would silently use Jackson 2 (it comes with keycloak-admin-client), so `JpaConfig` sets
  `Jackson3JsonFormatMapper` with the application `JsonMapper`. Replace the value instead of mutating
  a node in place (dirty checking compares values).
* Arrays: `absence.pairs`, `calendar_period.courses` are `smallint[]` mapped to `Short[]`; the entities
  expose `List<Integer>` getters and validated setters.
* Small numbers (weekday 1..5, pair 1..8, subgroup, course, shift) are `integer` columns / `int` fields.
  Dates are `date`/`LocalDate` (Kyiv), instants `timestamptz`/`Instant` (UTC, `hibernate.jdbc.time_zone=UTC`).
* **Flush order:** Hibernate executes all INSERTs before UPDATEs and DELETEs of one flush. Therefore
  - lesson slot uniqueness (teacher / exclusive room / cohort among SCHEDULED) is implemented as
    `EXCLUDE … DEFERRABLE INITIALLY DEFERRED` constraints `uq_lesson_teacher_slot`,
    `uq_lesson_room_slot`, `uq_lesson_cohort_slot` (same semantics as the partial unique indexes of
    DOMAIN §3, checked at commit). Applying a change (cancel original + insert new lesson in the same
    slot) and SWAP work in one transaction. Call `lessonRepository.checkSlotConstraintsNow()` to get
    the violation inside the service (it flushes and raises `DataIntegrityViolationException`,
    SQLState 23P01) and translate it to a 409 with `conflicts[]`;
  - unique keys of child rows are deferrable too (`uq_bell_slot`, `uq_load_item_teacher_subgroup`,
    `uq_calendar_period_group`, `uq_teacher_discipline`, `uq_subgroup_group_number`,
    `uq_teacher_preference_slot`, `uq_week_parity_override`, `uq_shift_window`), so "clear and
    re-add" of a collection works;
  - `hibernate.order_inserts` is **off** on purpose: without associations Hibernate does not know FK
    dependencies and would insert children before parents. Persist parents first (natural order).
* Other DB guards beyond DOMAIN: one active semester, one PUBLISHED timetable version per semester,
  one main ORDER per semester and date, unique order number per semester (assigned on publish under
  `orderDocumentRepository.lockNumbering(semesterId)` — a transaction-scoped advisory lock — then
  `findMaxNumber + 1`), `kind = ORDER ⇔ parent_id IS NULL`, PUBLISHED ⇒ number and published_at,
  lessons only Mon–Fri, `week_start` is a Monday, `pairs`/`courses` arrays validated.
* Whole-group vs subgroup overlap: lock the group's lessons of the date with
  `lessonRepository.lockGroupDay(groupId, date)` (`SELECT … FOR UPDATE`) and check in code.
* Nullable on purpose: `room.capacity` (unknown ⇒ skip the capacity check), `student_group.size`,
  `student_group.number` («4ВЕ»), `specialty.code`, `specialty.department_id`, `teacher.first_name`,
  `teacher.middle_name` (imported short names only).
* `open-in-view` is off; `ddl-auto=validate` — an entity that does not match the migrations fails the
  context (CoreApplicationIT, every `@CoreJpaTest`).

### Migrations

* core: `services/core/src/main/resources/db/migration`, **3-digit version ranges per module**:
  shared `V1_0xx`, catalog `V1_1xx`, calendar `V1_2xx`, load `V1_3xx`, timetable `V1_4xx`,
  substitution `V1_5xx`, order `V1_6xx`, accounting `V1_7xx`, identity/settings `V1_8xx`, demo seed
  `V9_xxx`. Foundation used `V1_001, V1_002, V1_100, V1_200, V1_300, V1_400, V1_500, V1_600, V1_800`;
  next free numbers: `V1_003`, `V1_101`, `V1_201`, `V1_301`, `V1_401`, `V1_501`, `V1_601`, `V1_700`,
  `V1_801`. Name: `V1_4NN__short_description.sql`; always three digits after `1_`.
* `spring.flyway.out-of-order=true` (env `FLYWAY_OUT_OF_ORDER`) because modules add migrations in
  parallel. Never edit an applied migration (checksum!) — add a new one. A FK into another module's
  table is fine at DB level; keep the Java side as a UUID field.
* Reference data (bell profiles, shift windows, settings defaults) is **not** in the schema
  migrations: the seed (`V9_xxx` with profile `demo`, or an idempotent `ApplicationRunner` under
  `app.seed.enabled`) creates it.
* scheduler / notification: `V1__processed_event.sql`; continue with `V2__…`. importer has no
  migrations yet (Flyway logs "No migrations found" until the first one).

## 8. Events and messaging

* Records in `libs/contracts` (`ua.vtc.rozklad.contracts.events`), sealed interface `RozkladEvent`
  (flat JSON: `eventId`, `occurredAt`, `version` + payload). Create with
  `new ScheduleChanged(EventEnvelope.create(clock), …)`. `RozkladEvents` holds exchange
  `rozklad.events` (topic), DLX `rozklad.dlx` (direct), routing keys, queue names and their bindings,
  `routingKey(event)`, `eventType(routingKey)`.
* **core publishes** with `ApplicationEventPublisher.publishEvent(event)` **inside a transaction**;
  Spring Modulith stores it in `event_publication` and sends it after commit (`EventExternalizationConfig.OUTBOUND`:
  timetable.version.published, schedule.changed, generation.requested, generation.cancel,
  substitution.confirmation-requested, substitution.status-changed, absence.reported,
  order.published). Rolled-back transactions send nothing; incomplete publications are re-sent on
  restart. `generation.progress/completed` received from the scheduler are never re-sent.
* Other services publish with `RabbitTemplate.convertAndSend(RozkladEvents.EXCHANGE, routingKey, event)`
  after their transaction commits.
* Consumers: `@RabbitListener(queues = RozkladEvents.Queues.CORE_SCHEDULER_RESULTS)` etc. Queues are
  declared by each consuming service (`scheduler.q.generation`, `core.q.scheduler-results`,
  `notification.q.events`) with `x-dead-letter-exchange=rozklad.dlx` → `<queue>.dlq`. Retries: 3
  attempts (Boot 4.1 property `max-retries: 2`, exponential 1 s → 10 s), then the message goes to the DLQ.
* Idempotency in core: inside the listener's `@Transactional` method call
  `processedEvents.markProcessed(event.eventId())` first and return if it is `false` (it requires an
  active transaction; ids older than 30 days are purged nightly). scheduler/notification have the
  same `processed_event` table and implement their own helper.
* One `MessageConverter` per service: `JacksonJsonMessageConverter` with the application `JsonMapper`
  and trusted package `ua.vtc.rozklad.contracts.events`. In core
  `spring.modulith.events.rabbitmq.enable-json=false` keeps Modulith from replacing it on the
  `RabbitTemplate` (its converter trusts no packages).
* `order.published` JSON is `{eventId, occurredAt, version, order: OrderPayload}`; `OrderPayload` is
  also the body of `POST /api/documents/orders/render`.

## 9. Shared kernel of core (`ua.vtc.rozklad.core.shared`)

`domain` (Ids, IdentifiableEntity, BaseEntity) · `time.TimeConfig` (`Clock` Europe/Kyiv, `KYIV`) ·
`persistence.JpaConfig` · `error.*` · `security.*` (SecurityConfig, CurrentUser, CurrentUserProvider,
Roles, KeycloakProperties `app.keycloak.*` incl. `admin.client-id/client-secret` for the identity
module's admin client) · `web.*` (RequestIdFilter + MDC `requestId`, `PageResponse.of(page, mapper)`
→ `{items,page,size,total}`, JacksonConfig: `LocalTime` written as `HH:mm`, OpenApiConfig, WebConfig)
· `sse.*` (`SseBroadcaster.sendToUser(uuid, type, data)`, `sendToRole("ADMIN", …)`, `broadcast(…)`,
`SseEventTypes`, `GET /api/events/stream`, 25 s `ping`, 30 min emitter timeout — EventSource
reconnects) · `messaging.*` (topology, outbox config, ProcessedEvents) · `AppProperties`
(`app.public-web-url`) · `SchedulingConfig` (`@EnableScheduling`, off with `app.scheduling.enabled=false`).

## 10. Tests

* `*Test` = unit/slice (surefire), `*IT` = Docker/Testcontainers (failsafe, runs in `verify`).
* core test support (`src/test/java/ua/vtc/rozklad/core/support`):
  `@CoreIntegrationTest` (full app on a random port + `RestTestClient`, shared context and
  containers), `@CoreJpaTest` (JPA slice on real PostgreSQL with Flyway, auditing, Jackson),
  `TestcontainersConfiguration` (postgres:18-alpine, rabbitmq:4.4-management-alpine, redis:8.8-alpine
  via `@ServiceConnection`), `WebSliceTestConfiguration` for `@WebMvcTest` (+ `@MockitoBean JwtDecoder`),
  `TestJwts.admin()/teacher()/service()` (MockMvc tokens with the demo user ids).
* Services: `support/TestcontainersConfiguration` + `*ApplicationIT`.
* Boot 4 test packages: `org.springframework.boot.webmvc.test.autoconfigure.{WebMvcTest,AutoConfigureMockMvc}`,
  `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`,
  `org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient`,
  `org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient`;
  `@MockitoBean`/`@TestBean` replace `@MockBean`.

## 11. Gateway (complete)

Routes (ARCHITECTURE §1): `/api/import/**` → importer, `/api/notify/**` and `/api/documents/**` →
notification, `/api/scheduler/**` → scheduler, `/api/events/stream` → core without response timeout
(SSE), `/api/public/**` → core with Redis `RequestRateLimiter` (per client IP, 20 req/s, burst 40;
`PUBLIC_RATE_LIMIT_*`), `/api/**` → core. Default filters: `PreserveHostHeader` (services build
absolute URLs with the client's host), `DedupeResponseHeader` for CORS headers. CORS for
`CORS_ALLOWED_ORIGINS` (5180, 8088) incl. preflight on unmatched paths, exposed headers `Location`,
`Content-Disposition`, `X-Request-Id`, `X-RateLimit-*`. `X-Request-Id`: kept if safe
(`[A-Za-z0-9._-]{1,64}`), otherwise generated; forwarded and returned. Gateway's own errors (no
route 404, service down 503, timeout 504) are ProblemDetail in Ukrainian. Response timeout 120 s
(`GATEWAY_RESPONSE_TIMEOUT`), connect 5 s. X-Forwarded headers are stripped (no trusted proxies),
the rate-limit key ignores `X-Forwarded-For` (forgeable).

## 12. Gotchas met (Boot 4.1 / Jackson 3 / Modulith 2.1 / Keycloak 26 / SCG 5)

* Starters: `spring-boot-starter-security-oauth2-resource-server` (the old `…-oauth2-resource-server`
  is deprecated), `spring-boot-starter-restclient` for `RestClient.Builder`, test starters
  `spring-boot-starter-<tech>-test`.
* Jackson 3: `tools.jackson.databind.*` (`JsonMapper`, `JsonNode.asString()`), annotations stay
  `com.fasterxml.jackson.annotation`; java.time is built in (`tools.jackson.databind.ext.javatime`).
  Customize with `JsonMapperBuilderCustomizer` (`org.springframework.boot.jackson.autoconfigure`).
* Hibernate 7.4 prefers Jackson 2 for JSON columns when present → set `Jackson3JsonFormatMapper`
  (done in `JpaConfig` / `InfrastructureConfig`). `@DataJpaTest` has no Jackson auto-config →
  `@CoreJpaTest` imports it.
* `HibernatePropertiesCustomizer` is in `org.springframework.boot.hibernate.autoconfigure`;
  `ErrorWebExceptionHandler` in `org.springframework.boot.webflux.error`.
* Modulith 2.1 `event_publication` v2 schema (status, completion_attempts, last_resubmission_date) —
  `V1_001`. Archive completion mode would need `event_publication_archive` (not created).
* Modulith's AMQP auto-config replaces the RabbitTemplate converter → disabled (see §8).
* Boot 4.1 Rabbit retry uses `max-retries` (`max-attempts` is deprecated).
* Testcontainers 2.0: artifacts `testcontainers-postgresql`, `testcontainers-rabbitmq`,
  `testcontainers-junit-jupiter`; classes `org.testcontainers.postgresql.PostgreSQLContainer`,
  `org.testcontainers.rabbitmq.RabbitMQContainer`; Redis: `com.redis.testcontainers.RedisContainer`.
* Timefold 2.8: no `timefold-solver-test` artifact in the BOM (find `ConstraintVerifier` in core 2.x
  before adding a test dependency); the starter logs a warning and skips itself while there is no
  `@PlanningSolution` class.
* Spring Cloud Gateway 5: properties under `spring.cloud.gateway.server.webflux.*`; forwarded headers
  are only added when `trusted-proxies` is set.
* Keycloak 26.8: `KC_BOOTSTRAP_ADMIN_*`; health on management port 9000; the image has no curl
  (health checks use bash `/dev/tcp`).
* PostgreSQL 18 image: mount the volume at `/var/lib/postgresql`.
* `eclipse-temurin:25-jre` is Ubuntu 26.04 without curl/wget; importer/notification use
  `eclipse-temurin:25-jre-noble` + `libreoffice-writer-nogui fonts-liberation fonts-dejavu`,
  `SOFFICE_PATH=/usr/bin/soffice`, user `app` (uid 10001) with a home for the LibreOffice profile.
* Windows/Git Bash: paths contain Cyrillic and spaces — quote them; `-Dbuild.dir='${…}'` in single quotes.

## 13. Known gaps (for the next agents)

* No domain services/controllers: catalog CRUD, calendar, load, timetable, substitution, orders,
  accounting, identity (invites + Keycloak admin client), settings — all to be implemented on top of
  the entities and repositories.
* Demo seed (DOMAIN §12) and reference data (bell profiles, shift windows, default settings) are not created.
* scheduler: no Timefold model, no `generation_job` table, no listeners. importer: no tables, parsers,
  AI port. notification: no bot, bindings, delivery queue, renderers.
* Redis is wired (connection + health) in core, importer, notification, but nothing uses it yet.
* `web/Dockerfile` is the web agent's; `docker compose --profile app up --build` fails until it exists.
* Verified images: `rozklad/core:dev` (ran healthy in the compose network, validated real Keycloak tokens
  with JWKS from `keycloak:8080`) and `rozklad/notification:dev` (LibreOffice 24.2 converts a seed DOCX
  to PDF as user `app`). scheduler, importer and gateway images use the same Dockerfiles but were not built.
* Completed rows of `event_publication` are kept (completion mode UPDATE). Add a daily cleanup with
  Modulith's `CompletedEventPublications.deletePublicationsOlderThan(Duration)` when the outbox grows.
* The client-credentials `RestClient`s are unit-tested against a fake token endpoint
  (`ServiceClientsConfigTest` in notification), not yet end-to-end against Keycloak + core.
