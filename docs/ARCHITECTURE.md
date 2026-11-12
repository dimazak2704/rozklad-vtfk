# Architecture — «Розклад ВТФК»

Web system for Vinnytsia Technical Professional College (ВТФК): timetable (base schedule with
numerator/denominator weeks), automatic generation, daily substitutions with ranked candidates,
teacher confirmation in Telegram, auto-generated orders («розпорядження») in the college format,
distribution to group chats, and hours accounting (plan vs fact).

Companion specs (read them before coding):
- `docs/DOMAIN.md` — glossary, business rules, core DB schema, Excel/order formats.
- `docs/CONTRACTS.md` — REST endpoints and RabbitMQ events between services and the SPA.
- `docs/UI.md` — pages, routes, UX rules, «wow» features.

## 1. Runtime topology

```
Browser (React SPA, :5180 dev / :8088 nginx)
   │  OIDC Authorization Code + PKCE ──────────────► auth :8085 at <origin>/auth (sign-in service,
   │                                                 Spring Authorization Server; nginx/Caddy proxy /auth)
   ▼
gateway :8080 (Spring Cloud Gateway Server WebFlux: routing, CORS, rate limit, X-Request-Id)
   ├── /api/import/**     → importer      :8083   (files, parsers, AI extraction)
   ├── /api/notify/**     → notification  :8084   (Telegram bot, bindings, deliveries)
   ├── /api/documents/**  → notification  :8084   (DOCX/PDF/PNG of orders)
   ├── /api/scheduler/**  → scheduler     :8082   (health/status only)
   └── /api/**            → core          :8081   (domain: catalog … accounting, SSE)

core ──(outbox via Spring Modulith → RabbitMQ topic exchange "rozklad.events")──► scheduler, notification
scheduler ──(events)──► core           notification ──(REST, client-credentials)──► core, importer
importer ──(REST, client-credentials)──► core

core ──(REST, client-credentials)──► auth /auth/internal/accounts (accounts of invites and «Доступи»)

PostgreSQL 18 :5433(host)/5432 — databases: core, scheduler, importer, notification, auth
RabbitMQ 4.4 :5672 (mgmt :15672)    Redis 8 :6379 (cache, bot dialog state, rate limits, idempotency)
```

Host port 5432 is taken by a local PostgreSQL service and 5173 by another dev server, therefore
Docker Postgres is published on **5433** and the Vite dev server runs on **5180** (`strictPort`).

## 2. Stack (pinned)

| Area | Choice |
|---|---|
| JDK | Temurin **25** (`scripts/env.sh` finds it; `ROZKLAD_JAVA_HOME` overrides) |
| Backend | Spring Boot **4.1.2**, Spring Framework 7, Spring Security 7, Hibernate 7, **Jackson 3** (`tools.jackson.*`, `JsonMapper`) |
| Gateway | Spring Cloud **2025.1.4** (`spring-cloud-starter-gateway-server-webflux`) |
| Modular core | Spring Modulith **2.1.2** (JPA event publication registry + AMQP externalization) |
| Solver | Timefold Solver **2.8.0** (`timefold-solver-spring-boot-starter`, Boot 4 only) |
| DB | PostgreSQL 18 + Flyway (via `spring-boot-starter-flyway`) |
| Messaging | RabbitMQ 4.4 (`spring-boot-starter-amqp`) |
| Cache/state | Redis 8 (`spring-boot-starter-data-redis`) |
| Auth | own service `auth`: `spring-boot-starter-security-oauth2-authorization-server` (Spring Authorization Server in Spring Security 7), Spring Session JDBC, Thymeleaf; services: `spring-boot-starter-oauth2-resource-server` |
| Docs/API | springdoc-openapi **3.2.0** |
| Office | Apache POI **5.5.1**, PDFBox **3.0.8**, LibreOffice headless in containers (DOC→DOCX, DOCX→PDF) |
| AI | `com.anthropic:anthropic-java` **3.6.0**; default model `claude-haiku-5-5`, vision fallback `claude-sonnet-5-5` (configurable) |
| Telegram | `org.telegram:telegrambots-longpolling` + `telegrambots-client` **10.3.0** |
| Tests | JUnit 5, AssertJ, Mockito, Testcontainers 2.x (Boot-managed), Spring Modulith test, ArchUnit |
| Frontend | React 19.3, TypeScript 6.0 (strict), Vite 8, React Router 8, TanStack Query 5, Zustand 5, Tailwind CSS 4.3, Radix (`radix-ui`), Motion 14, dnd-kit, cmdk, sonner, react-hook-form 7 + zod 4, oidc-client-ts + react-oidc-context, recharts 3, Vitest 5 + Testing Library, Playwright 1.65, ESLint 10 + typescript-eslint 8 |

Boot 4 gotchas: starters are modular (`spring-boot-starter-webmvc`, `spring-boot-starter-flyway`,
`spring-boot-starter-webmvc-test`, …); Jackson 3 packages are `tools.jackson.databind.*` while
annotations stay `com.fasterxml.jackson.annotation.*`; Security config is lambda DSL only.
**Verify any version-sensitive API with Context7 before relying on memory.**

## 3. Repository layout

```
rozklad/
├── pom.xml                      parent (Boot 4.1.2), BOMs, plugin management, modules
├── .mvn/maven.config            named file locks → safe parallel Maven runs
├── libs/contracts/              shared event payload records (ua.vtc.rozklad.contracts.*)
├── services/
│   ├── core/                    ua.vtc.rozklad.core      (Spring Modulith modular monolith)
│   ├── scheduler/               ua.vtc.rozklad.scheduler (Timefold)
│   ├── importer/                ua.vtc.rozklad.importer  (POI, AI extraction, normalization)
│   ├── notification/            ua.vtc.rozklad.notification (Telegram, documents, delivery)
│   ├── auth/                    ua.vtc.rozklad.auth      (sign-in: OIDC provider, accounts, sign-in page)
│   └── gateway/                 ua.vtc.rozklad.gateway
├── web/                         React SPA (Vite)
├── infra/
│   ├── docker-compose.yml       full stack (profiles: infra, app)
│   ├── postgres/init.sql        databases + users per service
│   ├── dev/                     demo passwords, Google mock config (development only)
│   └── deploy/                  production overlay, Caddy, deploy scripts (docs/DEPLOY.md)
├── seed/                        demo data inputs (copied Excel, sample programs, sample order photos)
├── docs/                        specs (this file, DOMAIN, CONTRACTS, UI) + ADRs
└── scripts/                     env.sh, helper scripts
```

## 4. Services

### core (domain owner)
Owns ALL domain data: catalog, calendar, load, timetable versions, template lessons, dated
lessons, absences, changes (substitutions), orders, accounting, settings, teacher↔account link.
Spring Modulith application modules = direct sub-packages of `ua.vtc.rozklad.core`:

| Module | Responsibility |
|---|---|
| `shared` | base classes, errors (ProblemDetail), time (`Europe/Kyiv` clock), SSE broadcaster, security helpers. Open module. |
| `catalog` | departments, specialties, groups, subgroups, teachers, cycle commissions, disciplines (+aliases), rooms, buildings, teacher↔discipline competence |
| `calendar` | academic years, semesters, week parity (+overrides), bell profiles, calendar periods (holidays, practice, study modes) |
| `load` | load items (навантаження): hours/week per group×discipline×semester, teachers, subgroup split |
| `timetable` | versions, template lessons, conflict checks, publication → materialized dated lessons, schedule queries (public), metrics, version diff, generation jobs (talks to scheduler via events) |
| `substitution` | absences, affected lessons, candidate ranking, changes, confirmation workflow |
| `order` | розпорядження: drafts per date, numbering, items, notes, publication, amendments |
| `accounting` | plan/fact/forecast per group×discipline, teacher substitution stats |
| `identity` | current user resolution, invites and password links, access grants («Доступи»), teacher↔account link; accounts live in `auth` |
| `settings` | key/value system settings with typed accessors |

Rules: modules talk via public API types in their base package (e.g. `catalog.CatalogQueries`)
or via Spring application events (`@ApplicationModuleListener`). Internals live in sub-packages
(`catalog.internal…`). `ApplicationModules.of(CoreApplication.class).verify()` must pass.

### scheduler
Stateless-ish Timefold service. Consumes `generation.requested` (full problem snapshot), solves with
time limit, emits `generation.progress` every ~2 s and `generation.completed` (solution + score
analysis + metrics). Supports cancel (`generation.cancel`). Keeps a small `generation_job` table
(own DB) for status/idempotency. No access to core DB.

### importer
Accepts uploads, stores files (own DB, bytea or filesystem volume), parses:
- `TIMETABLE_XLSX` — the college Excel format (deterministic POI parser; see DOMAIN §9);
- `DISCIPLINE_PROGRAM` — DOCX/DOC/PDF programs (deterministic table parser first, LLM fallback);
- `CURRICULUM` — scanned PDF curriculum (vision LLM) or XLSX;
- `ORDER_DOCUMENT` — DOCX or photo/screenshot of a розпорядження (POI for DOCX, vision LLM for images).
Produces a **draft** with a normalization report (matched / unknown / conflicts / suggestions) against a
catalog snapshot fetched from core, lets admin fix mappings, then **applies** via core REST.
Also serves `ai/parse-absence` (free text from the bot) and AI usage accounting with a daily budget.
AI access goes through a port (`AiExtractionPort`) with an Anthropic adapter and a deterministic fake
for tests. Import never writes core data directly.

### notification
Telegram bot (long polling by default, webhook optional), chat bindings (group chats, teacher private
chats, admin chat), outbound queue with per-chat and global rate limiting, retries with backoff,
delivery log; renders orders to DOCX (POI, college layout) → PDF (LibreOffice) → PNG (PDFBox).
Consumes `order.published`, `substitution.confirmation-requested`, `substitution.details-changed`, `absence.reported`,
`schedule.changed`, `generation.completed`. Calls core internal REST for confirmations and absences,
core public REST for schedule replies, importer for free-text absence parsing.

### auth
Sign-in service (`docs/modules/auth.md`): OIDC provider for the SPA, scripts and the service clients,
accounts with BCrypt passwords and Google bindings, lockout, sign-in sessions, the signing key. Own
database `auth`; core manages accounts through its internal API. Not behind the gateway: the browser
reaches it on the site's own origin under `/auth`.

### gateway
Routes (see topology), CORS for the SPA origins, Redis rate limiting on `/api/public/**`,
`X-Request-Id` generation/propagation, passes `Authorization` through. Services validate JWTs themselves.

## 5. Messaging

- Exchange `rozklad.events` (topic, durable). Routing keys = event names (`order.published`, …).
- Each consumer service declares its own durable queues bound to the keys it needs:
  `scheduler.q.generation`, `core.q.scheduler-results`, `notification.q.events`; each with a DLQ
  (`*.dlq`) via dead-letter exchange `rozklad.dlx`.
- Producers in core use **Spring Modulith event externalization** (`@Externalized("rozklad.events::<key>")`)
  with the JPA event publication registry ⇒ transactional outbox semantics.
  Other services publish with `RabbitTemplate` after commit.
- Payloads are JSON (Jackson 3) of records from `libs/contracts`; every event carries
  `eventId` (UUID), `occurredAt` (ISO instant), `version` (int).
- Consumers are **idempotent**: store processed `eventId` (table `processed_event` or Redis SETNX with TTL).
- Retries: Spring AMQP retry interceptor (3 attempts, exponential backoff) then DLQ.

## 6. Security

Sign-in service `auth` (`services/auth`, details in `docs/modules/auth.md`):
- One fixed issuer per environment: `http://localhost:8088/auth` in development (nginx of the `web`
  container), `https://<DOMAIN>/auth` on the server (Caddy). Containers fetch keys from
  `http://auth:8085/auth/oauth2/jwks` (`AUTH_JWKS_URI`), so resource servers configure the JWKS URI and
  validate `iss` against `AUTH_ISSUER` explicitly (custom `JwtDecoder` with
  `JwtValidators.createDefaultWithIssuer`) plus `aud` ∋ `AUTH_AUDIENCE` (`rozklad-api`).
- Roles: `ADMIN` (dispatcher / deputy director), `TEACHER`, `SERVICE` (+ the service's own `SERVICE_NOTIFICATION` or
  `SERVICE_IMPORTER`), in the flat `roles` claim;
  `TokenRoles` maps them to `ROLE_<NAME>` authorities. `sub` is the account UUID, `preferred_username` the
  actor name (`service-account-<client>` for services).
- Clients: `rozklad-web` (public, PKCE S256, redirect `<origin>/login/callback` and
  `<origin>/login/silent.html` for 5180, 5181 and 8088 in development), `rozklad-cli` (public, loopback,
  development only), `rozklad-notification` and `rozklad-importer` (`client_credentials`, roles `SERVICE` + `SERVICE_NOTIFICATION` / `SERVICE_IMPORTER`),
  `rozklad-core` (`client_credentials`, scope `accounts.manage` for the internal accounts API).
- The SPA has no refresh token: it renews in a hidden iframe with `prompt=none` while the sign-in session lives.
- Demo users (development, fixed ids so core can link teachers): `admin` (ADMIN), `krentsin` and
  `nepyivoda` (TEACHER); passwords in `infra/dev/demo-users.env`.
- Authorization: `/api/public/**` permitAll; `/api/admin/**` → ADMIN; `/api/me/**` → TEACHER or ADMIN;
  `/api/internal/**` → the calling service's own role (bot endpoints SERVICE_NOTIFICATION, catalog snapshot
  SERVICE_IMPORTER, the rest denied); `/api/admin/import/**` → ADMIN or SERVICE_IMPORTER; actuator health permitAll. Ownership: a teacher may read/modify only
  their own absences, preferences and assigned changes (checked in services, not only by role).
- Registration = invite: admin creates a teacher → `POST /api/admin/invites` → link `/invite/{token}`
  → teacher sets username/password → core creates or updates the account in `auth` through the internal
  API and links `teacher.account_id`. Passwords are BCrypt hashes in `auth`. Access by e-mail (Google or a
  password link) is granted on «Доступи» (`/admin/access`).
- Secrets only via env (`.env`, never committed). Telegram webhook (if enabled) checks the secret token header.

## 7. Configuration (env)

| Var | Used by | Default (dev) |
|---|---|---|
| `DB_HOST`/`DB_PORT` | all JVM services | `localhost`/`5433` (host), `postgres`/`5432` (compose) |
| `DB_NAME`,`DB_USER`,`DB_PASSWORD` | per service | `core`/`core`/`core`, … |
| `RABBIT_HOST`,`RABBIT_PORT`,`RABBIT_USER`,`RABBIT_PASSWORD` | all | `localhost`,`5672`,`rozklad`,`rozklad` |
| `REDIS_HOST`,`REDIS_PORT` | gateway, notification, importer, core | `localhost`,`6379` |
| `AUTH_ISSUER` | resource servers, auth | `http://localhost:8088/auth` |
| `AUTH_JWKS_URI` | resource servers | `http://localhost:8085/auth/oauth2/jwks` (host), `http://auth:8085/auth/oauth2/jwks` (compose) |
| `AUTH_AUDIENCE` | resource servers | `rozklad-api` |
| `AUTH_TOKEN_URI` | core, notification, importer | `http://localhost:8085/auth/oauth2/token` (host), `http://auth:8085/…` (compose) |
| `AUTH_INTERNAL_URL` | core | `http://localhost:8085/auth/internal` |
| `CORE_AUTH_CLIENT_SECRET`, `NOTIFICATION_CLIENT_SECRET`, `IMPORTER_CLIENT_SECRET` | auth and the client | dev values in `.env.example` |
| `AUTH_*` of the sign-in service | auth | `docs/modules/auth.md`, `services/auth/src/main/resources/application.yml` |
| `CORE_URL`,`IMPORTER_URL` | notification, importer | `http://localhost:8081`, `http://localhost:8083` |
| `PUBLIC_WEB_URL` | core, notification | `http://localhost:5180` |
| `TELEGRAM_BOT_TOKEN`, `TELEGRAM_OWNER_CHAT_ID` | notification | from `.env` |
| `ANTHROPIC_API_KEY`, `AI_MODEL_TEXT`, `AI_MODEL_VISION`, `AI_DAILY_BUDGET_USD` | importer | key from `.env`, `claude-haiku-5-5`, `claude-sonnet-5-5`, `2.00` |
| `SOFFICE_PATH` | importer, notification | `soffice` (container), empty on host ⇒ conversion disabled gracefully |

## 8. Persistence conventions

- One DB per service; Flyway migrations in `src/main/resources/db/migration`.
  Core uses version ranges per module to avoid collisions: shared `V1_0xx`, catalog `V1_1xx`,
  calendar `V1_2xx`, load `V1_3xx`, timetable `V1_4xx`, substitution `V1_5xx`, order `V1_6xx`,
  accounting `V1_7xx`, identity/settings `V1_8xx`, demo seed `V9_xxx` (only with profile `demo`,
  or idempotent seed via Java `ApplicationRunner` when `app.seed.enabled=true`).
- PK `uuid` generated in Java; `created_at`, `updated_at` (`timestamptz`), `version` (optimistic lock) on
  mutable aggregates. snake_case tables/columns. FKs + indexes on FK columns. JSONB for drafts/reports.
- `ddl-auto=validate`. No business logic in triggers.
- Dates are `date` in `Europe/Kyiv`; instants are `timestamptz` (UTC).

## 9. API conventions

- REST + JSON, plural nouns, ISO-8601 dates. Errors: RFC 9457 `ProblemDetail` with `type`,`title`,
  `detail`, `errors[]` for validation (`field`,`message` in Ukrainian for UI display).
- Status codes: 200/201(+Location)/204, 400 validation, 401, 403, 404, 409 conflict (schedule
  conflicts carry a `conflicts[]` array), 422 business rule violation.
- Pagination: `?page=0&size=20&sort=name,asc` → `{items,page,size,total}`.
- Bean Validation on request DTOs; server-side validation is mandatory (methodology).
- OpenAPI at `/v3/api-docs`, Swagger UI at `/swagger-ui.html` per service.
- SSE: `GET /api/events/stream` (core) with event types listed in CONTRACTS.

## 10. Observability

Actuator `health` (liveness/readiness groups), `info`, `metrics`; structured console logs (Boot 4
structured logging, `logging.structured.format.console=ecs` in compose), `X-Request-Id` propagated and
put into MDC: forwarded on service-to-service calls, carried by event messages as a header with
`message_id` = `eventId` (CONTRACTS §7) and put back into the consumer's MDC (`requestId`, `eventId`). Never log tokens, API keys, Telegram tokens, or absence reasons/comments.

## 11. Testing

- Unit tests for domain logic (parity, materialization, conflicts, ranking, numbering, accounting,
  parsers, normalization, constraint provider via Timefold `ConstraintVerifier`).
- Slice/integration tests with Testcontainers (PostgreSQL, RabbitMQ) for repositories, Flyway, REST
  controllers (`@WebMvcTest` + `@SpringBootTest`), event flows (Modulith `Scenario`).
- Frontend: Vitest + Testing Library for components/hooks; Playwright e2e for main flows.
- Keep tests deterministic: inject `Clock`, no sleeps, fixed seeds.

## 12. Build & run (Git Bash on Windows)

```bash
source scripts/env.sh                            # JDK 25 + Maven
mvn -q -pl services/core -am verify              # one module with deps
mvn -q verify                                    # everything
docker compose -f infra/docker-compose.yml --profile infra up -d     # postgres, rabbit, redis
docker compose -f infra/docker-compose.yml --profile app up -d --build # all services, auth + web
scripts/dev-stack.sh up                          # the same, with jars, health waits and checks
cd web && npm run dev                            # SPA on http://localhost:5180
```

## 13. Code conventions

- Java 25: records for DTOs/value objects, sealed types for variants, pattern matching, `var` sparingly.
  No Lombok. Constructor injection. `@Transactional` on application services, not controllers.
- Package by feature; controllers thin; no entity leakage in REST (map to DTO records).
- Identifiers in English; **short code comments in Ukrainian** only where the decision is not obvious;
  **all UI text and API validation messages in Ukrainian**.
- No `System.out`, no secrets in code, no commented-out code, no TODO without explanation.

## 14. Definition of done (per module/agent)

1. Code compiles on JDK 25; `mvn -q -pl <module> -am verify` is green (or `npm run build && npm run lint && npm test` for web).
2. Business rules from DOMAIN.md for the module are implemented and unit-tested, including error paths.
3. Endpoints/events match CONTRACTS.md exactly (names, fields, status codes). Any deviation is documented
   in `docs/CHANGELOG-CONTRACTS.md` with the reason.
4. Configuration via env with dev defaults; works both on host and in docker compose.
5. A short module README section in `docs/modules/<module>.md`: what it does, how to run/test, known gaps.
