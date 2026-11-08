# Running the whole system locally with real data

How to start the five services against the Docker infrastructure, fill them with the college's real
data through the system's own API, and check the main workflows. Everything here is for a development
PC (Windows 11 + Git Bash + Docker Desktop); the server setup is in `docs/DEPLOY.md`.

## 1. What runs where

| Container | Port (host) | Memory | Notes |
|---|---|---|---|
| postgres 18 | 5433 | — | databases `core`, `scheduler`, `importer`, `notification`, `keycloak` (`infra/postgres/init.sql`) |
| rabbitmq 4.4 | 5672, UI 15672 | — | `rozklad` / `rozklad` |
| redis 8 | 6379 | — | |
| keycloak 26.8 | 8180 | — | realm `rozklad` imported from `infra/keycloak/rozklad-realm.json` on the first start |
| core | 8081 | heap 512 MB, limit 900 MB | domain, SSE `/api/events/stream`, Swagger `/swagger-ui.html` |
| scheduler | 8082 | 512 / 900 MB | Timefold |
| importer | 8083 | 384 / 900 MB | LibreOffice in the image, AI with `ANTHROPIC_API_KEY` |
| notification | 8084 | 384 / 900 MB | Telegram bot, documents (LibreOffice) |
| gateway | **8080** | 256 / 512 MB | the only entry point for clients: `http://localhost:8080/api/...` |
| web (optional) | 8088 | — | nginx with the SPA; for development use `cd web && npm run dev` (5180) |

Heaps are set with `JAVA_TOOL_OPTIONS=-Xmx…` (wins over `-XX:MaxRAMPercentage=75` of the images) and
can be changed with `CORE_HEAP`, `CORE_MEM_LIMIT`, … in the shell or `.env`. The whole stack takes about
4.5 GB (Keycloak alone ~1 GB).

## 2. Requirements

* Docker Desktop with Compose v2; Git Bash.
* JDK 25 and Maven 3.9 (`source scripts/env.sh` sets them on this PC) — only to build the jars.
* Python 3.10+ (standard library only) for the seed and smoke scripts.
* `.env` in the repository root (gitignored, never committed). Only secrets live there:
  `TELEGRAM_BOT_TOKEN`, `TELEGRAM_OWNER_CHAT_ID`, `ANTHROPIC_API_KEY`. Everything else has dev defaults in
  `infra/docker-compose.yml` (client secrets are the dev values of the realm file). `.env.example` lists all
  variables.

## 3. Commands (`scripts/dev-stack.sh`)

```bash
scripts/dev-stack.sh up                 # infra + build jars and images + 5 services, waits until healthy
scripts/dev-stack.sh up --no-telegram   # the same, the bot stays off (empty token for notification)
scripts/dev-stack.sh up --no-build      # start from the images already built
scripts/dev-stack.sh up --web           # also the nginx SPA on :8088
scripts/dev-stack.sh status             # containers, health, memory, URLs
scripts/dev-stack.sh logs core          # follow one service
scripts/dev-stack.sh seed               # real data through the API (idempotent, ~3 min)
scripts/dev-stack.sh down               # stop the app services (infra and data stay)
scripts/dev-stack.sh down --all         # stop everything; data stays in the Docker volumes
scripts/dev-stack.sh reset-data --yes   # empty the four app databases, queues and Redis (Keycloak stays)
```

A fresh demo database: `reset-data --yes`, `up --no-build`, `seed`.

**Telegram.** One bot token can be polled by one process only. `up` without `--no-telegram` starts the bot
with the token from `.env`; stop any other copy (server, IDE run) first. `--no-telegram` (or `TELEGRAM=off`)
adds `infra/docker-compose.no-telegram.yml`, which empties the token: the bot and the delivery worker stay off,
everything else works. To switch the running stack later: `scripts/dev-stack.sh up --no-build`.

`up` also makes sure the Keycloak client `rozklad-core-admin` has the scope `kc-admin-api` (Keycloak 26.8
rejects admin REST calls without it; realms imported before 08.11.2026 lack it).

## 4. Demo users (test credentials)

Defined in `infra/keycloak/rozklad-realm.json`; the values the scripts use are in
`infra/keycloak/demo-users.env`. Development only — the server uses other passwords (`docs/DEPLOY.md` §9).

| Username | Role | Linked teacher |
|---|---|---|
| `admin` | ADMIN (dispatcher) | — |
| `krentsin` | TEACHER | Кренцін (СТП) |
| `nepyivoda` | TEACHER | Непийвода (НДЕКС, КБ) |

Token for scripts (public client `rozklad-dev-cli`, password grant):

```bash
source <(grep -E '^(ADMIN|TEACHER)_' infra/keycloak/demo-users.env)
TOKEN=$(curl -s -d client_id=rozklad-dev-cli -d grant_type=password -d "username=$ADMIN_USERNAME" \
  -d "password=$ADMIN_PASSWORD" http://localhost:8180/realms/rozklad/protocol/openid-connect/token \
  | python -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/me
```

## 5. What the seed creates (`scripts/seed_demo.py`)

Every step is a call to the running system (no SQL); a repeated run skips what exists.

1. **structure** — 5 departments, 11 specialties (КІ = 123 «Комп’ютерна інженерія»; others only letters,
   DOMAIN §12), 2 cycle commissions.
2. **calendar** — «I семестр 2026/2027» 01.09–30.12.2026 (week 1 = чисельник, active) and «II семестр
   2026/2027» 19.01–30.06.2027, bell profile «2 зміни, 60 хв» checked against the college bells; 19.10.2026 =
   week 8, знаменник.
3. **import** — `seed/schedule-2025-26.xlsx` uploaded as `TIMETABLE_XLSX` → parsed (56 groups, 1077 lessons,
   85 warnings: typos merged, two curators, clashes in the file) → review (READY) → apply: catalog (352
   entities) and the draft version «Розклад 2025/26 (імпорт з Excel)».
4. **periods** — from the notes of order №35: 3rd course online 01.09–31.10, 4КІ online 01.09–31.10,
   4ЕА/4ВЕ/4РТ/4ОП offline 01.09–31.10, practice of 2КІ3 19.10–30.10 (created before publication, which
   uses them).
5. **publish** — the Excel has 63 hard conflicts (61 rooms, mostly 3rd vs 4th course: the 3rd course studied
   online; 2 teachers). The seed resolves them in the editor like a dispatcher: the PE lessons of Папулова
   for 2ЕА1/2М/2Ф become a stream; a double-booked room stays with one lesson and the others (3rd course
   first) are left without a room; Овчар's NUM-only Матем. in 2КІ1 is left without a teacher. Then the version
   is published from 01.09.2026 (17 106 dated lessons) and 646 load items are derived from it.
6. **accounts** — `krentsin` and `nepyivoda` linked to their teachers (`PUT /api/admin/teachers/{id}/account`).
7. **order35** — order №35 of 19.10.2026 rebuilt from `seed/orders/order-035-2026-10-19.jpg`: numbering of the
   semester starts at 35 (`PUT /api/admin/orders/numbering`), teachers Ємельяненко and Яцуба and the
   discipline ЗУ «Захист України» added, absence of Крисак Л. (4 affected lessons), 16 changes (BLOCK 1ТК1 1-4,
   2 × ADDITIONAL, 2 × CANCEL of a subgroup, 2 × REPLACE_TEACHER, 9 × REPLACE_DISCIPLINE «Зв.зам.»), Непийвода
   confirms hers in the cabinet, the dispatcher confirms the rest, the 5 notes of the original, publication →
   №35. Printed sheet: `GET /api/documents/orders/{id}.pdf|png|docx`.

Differences from the paper original that the data does not allow to avoid: rows are sorted by course and
group (the original is unsorted), «Ін. мова Крисак Л.» of a subgroup prints «Крисак Л. (п.)», ADDITIONAL rows
print «ДОДАТКОВО» on the left, НДКС prints as the catalog's short name «НДЕКС», the duplicated last row
«1ОП2 4 БЖ Погосян 403» of the original exists once.

## 6. Checking the workflows (`scripts/smoke_workflows.py`)

```bash
python scripts/smoke_workflows.py public                  # read-only: meta, search, week schedules, changes, bells, iCal
python scripts/smoke_workflows.py substitution generation # creates data on 12.11.2026 and a generated draft
python scripts/smoke_workflows.py ai                      # ONE paid Anthropic call (curriculum scan)
```

`substitution`: absence of Непийвода → affected lessons → ranked options → change for Кренцін → he declines in
the cabinet → asked again → confirms → the rest cancelled → dashboard → order №36 → teacher's own schedule →
PDF; SSE events `absence.created`, `change.updated`, `order.published`, `schedule.changed` are checked on
`/api/events/stream` through the gateway. `generation`: 10-second Timefold run for 4КІ1/4КІ2, result version
readable. `ai`: the curriculum PDF through the importer's vision model, review, apply → load items. The
smoke run leaves data behind; reset and seed again for a clean demo.

To keep AI cheap while testing, put `AI_MODEL_VISION=claude-haiku-5-5` into `.env` and run `scripts/dev-stack.sh up --no-build`:
the curriculum costs ~$0.003 with Haiku 5.5 instead of ~$0.05 with Sonnet 5.5, but Haiku returned only 2 of the
12 rows Sonnet reads for semester 7 (`docs/modules/importer-ai-samples.md` §6) — use the default vision model
for real imports.

### The SPA against the stack (`web/e2e-real`)

An opt-in Playwright suite (not part of `npm run e2e`, which uses the mock API) drives the SPA through the
gateway with real Keycloak logins of the demo users. It starts its own Vite dev server on 5180 without mocks
(the only dev port the Keycloak client `rozklad-web` accepts); `REAL_BASE_URL=http://localhost:8088` runs it
against the nginx container instead (`up --web`), `REAL_REUSE_SERVER=1` reuses a dev server already on 5180.

```bash
cd web
npm run e2e:real                                   # desktop 1440, tablet 768, phone 375; headless
npx playwright test -c playwright.real.config.ts --project=desktop dispatcher   # one file
```

It checks the public pages against the API answers (week grid and day list of 4КІ1, parity of 19.10.2026,
teacher, room, bells, changes of order №35 with PDF/PNG), error and offline states, and on desktop the
dispatcher and teacher workflows, and the import review of a program without weekly hours (parameters fixed on the page, nothing applied; the job is left with empty parameters). It changes data: the substitution test creates an absence of Кренцін on one
pair, Непийвода confirms it in her cabinet and the order of that date is published (the next number, №36 on a
fresh seed). The date is the first school day a week ahead without an absence of Кренцін and without an order,
so the suite can run again; a two-day absence of Стець is created and cancelled. For clean demo data afterwards:
`reset-data --yes`, `up --no-build`, `seed`.

`REAL_STOP_SERVICES=1` adds one more desktop test: it stops the importer container (`docker stop`), checks that the
import page shows «Сервіс тимчасово недоступний» (gateway 503) with a retry while the rest of the panel works,
starts the container again and waits until it is healthy (about a minute).

## 7. Troubleshooting

* A service stays `starting`: `scripts/dev-stack.sh logs <service>`; the JSON lines have `log.level` and
  `message`.
* 503 «Сервіс входу недоступний» on invites or account links: the scope `kc-admin-api` is missing — run
  `scripts/dev-stack.sh up --no-build` (it adds it) or `infra/deploy/scripts/keycloak-sync.sh` on the server.
* The importer reports «Не вдалося отримати каталог із сервера розкладу»: core was down during parsing; save mappings
  (`PUT /api/import/jobs/{id}/mappings` with `{"mappings": []}`) to match again.
* `GET /api/documents/orders/{id}.pdf` is 404 for a moment after publication: notification gets the order
  through the `order.published` event.
* Port 5432 belongs to a local PostgreSQL, therefore Docker Postgres is on 5433.
