# Running the whole system locally with real data

How to start the six services against the Docker infrastructure, fill them with the college's real
data through the system's own API, and check the main workflows. Everything here is for a development
PC (Windows 11 + Git Bash + Docker Desktop); the server setup is in `docs/DEPLOY.md`.

## 1. What runs where

| Container | Port (host) | Memory | Notes |
|---|---|---|---|
| postgres 18 | 5433 | — | databases `core`, `scheduler`, `importer`, `notification`, `auth` (`infra/postgres/init.sql`) |
| rabbitmq 4.4 | 5672, UI 15672 | — | `rozklad` / `rozklad` |
| redis 8 | 6379 | — | |
| auth | 8085 (management 8086 in the container) | 256 / 512 MB | sign-in service (OIDC issuer `http://localhost:8088/auth`), database `auth` |
| core | 8081 | heap 512 MB, limit 900 MB | domain, SSE `/api/events/stream`, Swagger `/swagger-ui.html` |
| scheduler | 8082 | 512 / 900 MB | Timefold |
| importer | 8083 | 384 / 900 MB | parsers (Apache POI, PDFBox), AI with `ANTHROPIC_API_KEY` |
| notification | 8084 | 384 / 900 MB | Telegram bot, documents (LibreOffice) |
| gateway | **8080** | 256 / 512 MB | the only entry point for clients: `http://localhost:8080/api/...` |
| web | 8088 | — | nginx with the SPA, `/api` → gateway, `/auth` → auth; every sign-in goes through it (also from `npm run dev` on 5180) |

Every port is published on `127.0.0.1` only. The stand runs the real bot token from `.env` with public
development credentials (`rozklad`/`rozklad`, `postgres`/`postgres`, Redis without a password), so on a
shared network (college or café Wi-Fi) nobody else may reach it. To use the stand from a phone, sign-in
included, start it with `--lan` (below, «Phone on the LAN»): only Caddy's https port 443 is opened, behind a key. For a demo
away from home prefer the deployed site, or at least `--no-telegram`.

Heaps are set with `JAVA_TOOL_OPTIONS=-Xmx…` (wins over `-XX:MaxRAMPercentage=75` of the images) and
can be changed with `CORE_HEAP`, `CORE_MEM_LIMIT`, … in the shell or `.env`. The whole stack takes about
3.5–4 GB.

## 2. Requirements

* Docker Desktop with Compose v2; Git Bash.
* JDK 25 and Maven 3.9 (`source scripts/env.sh` sets them on this PC) — only to build the jars.
* Python 3.10+ (standard library only) for the seed and smoke scripts.
* `.env` in the repository root (gitignored, never committed). Only secrets live there:
  `TELEGRAM_BOT_TOKEN`, `TELEGRAM_OWNER_CHAT_ID`, `ANTHROPIC_API_KEY`. Everything else has dev defaults in
  `infra/docker-compose.yml` (the client secrets are dev values shared by `auth` and the services). `.env.example` lists all
  variables.

## 3. Commands (`scripts/dev-stack.sh`)

```bash
scripts/dev-stack.sh up                 # infra + build jars and images + 6 services + web, waits until healthy
scripts/dev-stack.sh up --no-telegram   # the same, the bot stays off (empty token for notification)
scripts/dev-stack.sh up --no-build      # start from the images already built
scripts/dev-stack.sh up --no-web        # without the nginx SPA on :8088 (then nobody can sign in)
scripts/dev-stack.sh up --google-mock   # automated tests only: a fake Google (mock-oauth2-server, :8090)
scripts/dev-stack.sh up --no-build --time 2026-11-20T10:00   # the stand lives at that Kyiv time (below)
scripts/dev-stack.sh up --no-build --lan                     # phones on the LAN: https://<this PC's IP> (below)
scripts/dev-stack.sh status             # containers, health, memory, URLs
scripts/dev-stack.sh logs core          # follow one service
scripts/dev-stack.sh seed               # real data through the API (idempotent, ~3 min)
scripts/dev-stack.sh down               # stop the app services (infra and data stay)
scripts/dev-stack.sh down --all         # stop everything; data stays in the Docker volumes
scripts/dev-stack.sh reset-data --yes   # empty the four app databases, queues and Redis (accounts stay)
scripts/dev-stack.sh reset-auth --yes   # drop the auth database; the next `up` creates the demo accounts again
```

A fresh demo database: `reset-data --yes`, `up --no-build`, `seed` (a living one: §5, «A living stand»).

**Telegram.** One bot token can be polled by one process only. `up` without `--no-telegram` starts the bot
with the token from `.env`; stop any other copy (server, IDE run) first. `--no-telegram` (or `TELEGRAM=off`)
adds `infra/docker-compose.no-telegram.yml`, which empties the token: the bot and the delivery worker stay off,
everything else works. To switch the running stack later: `scripts/dev-stack.sh up --no-build`.

**Stand time («машина часу»).** `up --time 2026-11-20T10:00` starts core, importer and notification as if it
were Friday 20.11.2026 10:00 in Kyiv, and the time ticks on from there: the site shows the second pair as
current, «сьогодні» is that Friday, the bot's «сьогодні», deadlines and reminders follow it, and the morning
digest comes at 07:30 of the next stand weekday. `status` prints the stand time, and `GET /api/public/meta`
adds `"now"` (the stand instant) and `"standTime": true`. `up` without `--time` returns to real time (the
three containers are recreated, data stays). Sign-in, tokens, the delivery queue and the outbox keep real
time; auth and scheduler are never shifted. Rows created under the stand time keep its dates, so after a
session with it `reset-data --yes` + `seed` gives a clean demo again. It is for the development stand only:
with an `https` issuer the services refuse to start. Details: `docs/ARCHITECTURE.md` §7.1.

**Phone on the LAN.** `up --lan` (or `--lan=192.168.1.20` when detection picks the wrong adapter) adds
`infra/docker-compose.lan.yml`: Caddy (`lan-edge`) listens on `<IP>:443` with a certificate from its own
local CA and passes everything to nginx web, the way Caddy does in production. Why https: sign-in uses PKCE,
which needs `crypto.subtle`, and browsers give it only to secure contexts (https or localhost), so
`http://192.168.x.x:8088` can never sign in. The issuer becomes `https://<IP>/auth` for auth and every service,
so while the mode is on, sign in at `https://<IP>` only, on the laptop too; `http://localhost:8088` still shows
the site. `ROZKLAD_LAN_DEV=true` keeps an https issuer on a private IPv4 address a development stand (demo
accounts, stand time); with a domain or a public address auth refuses to start. Google sign-in is off in this
mode (Google does not accept private addresses as redirect URIs): use the demo passwords. `up` without `--lan`
returns to `http://localhost:8088` and stops `lan-edge`. The edge never restarts by itself: after a reboot it
stays down until the next `up --lan` (and on another network its address is gone, so it cannot start).

The key gate. The demo passwords (`infra/dev/demo-users.env`) and the dev client secrets are public in this
repository, and Docker Desktop lets its ports through Windows Firewall on every network profile (its own rules
for `com.docker.backend`), so a firewall rule limited to Private networks protects nothing. What keeps other
devices on the same Wi-Fi out is a key: without the cookie `rozklad_lan`, Caddy answers 403 with a small form
to every page, sign-in and the API, and serves only the static files of the SPA (the web manifest and its
icons are fetched without cookies). A device gets in by opening the unlock link once (`status` prints it) or
by typing the key into the form; the cookie lasts 90 days. The key is made once and kept in
`infra/dev/certs/lan-key` (gitignored); delete the file and run `up --lan` again to lock every device out.
An installed iPhone web app keeps its own cookies, so it asks for the key once more. `up --lan` warns when
Windows marks the network as Public; the gate holds there too, but prefer a home network or the deployed site.

Once per PC and phone:
1. Windows Firewall: Docker Desktop's own rules already let port 443 in. Only if the phone cannot connect,
   add a rule in an elevated PowerShell: `New-NetFirewallRule -DisplayName "Rozklad LAN stand (443)"
   -Direction Inbound -Protocol TCP -LocalPort 443 -Profile Private -Action Allow`.
2. `up --lan` copies the root of Caddy's CA to `infra/dev/certs/rozklad-dev-ca.crt` (gitignored; the CA itself
   stays in the `caddy_lan_data` volume, so the phone trusts it once). Send the file to the phone.
3. iPhone: open the file, Settings → «Profile Downloaded» → Install; then Settings → General → About →
   Certificate Trust Settings → turn the root on. Android: Settings → Security → Encryption & credentials →
   Install a certificate → CA certificate. Without this the browser warns, and the PWA cannot be installed.
4. Open the unlock link `https://<IP>/?lan_key=<key>` printed by `status` (or open `https://<IP>` and type
   the key into the form).

`up` also creates the database `auth` on an older Postgres volume and checks, through nginx, that discovery
names the issuer `http://localhost:8088/auth` and that an authorize request without a session goes to
`/auth/login`. It removes containers of services that are no longer in the compose files. A volume created
before the sign-in service may still hold the unused database of the former identity provider; the command
that drops it is in `docs/CHANGELOG-CONTRACTS.md` (entry «Sign-in service only»).

## 4. Demo users (test credentials)

The sign-in service creates them on start (`AUTH_DEMO_SEED=true`, dev compose only) with the passwords of
`infra/dev/demo-users.env`, which the scripts and e2e-real read too. Development only — the server uses other
passwords (`docs/DEPLOY.md` §9). Sign in at `http://localhost:8088/login` (the SPA sends you to `/auth/login`).

| Username | Role | Linked teacher |
|---|---|---|
| `admin` | ADMIN (dispatcher) | — |
| `krentsin` | TEACHER | Кренцін (СТП) |
| `nepyivoda` | TEACHER | Непийвода (НДЕКС, КБ) |

Token for scripts and curl. There is no password grant: `scripts/rozklad_auth.py` signs in through the real
sign-in page over HTTP with the public client `rozklad-cli` (code + PKCE, loopback redirect; the client exists
only with `AUTH_CLI_ENABLED=true`, i.e. in development). The token lives 15 minutes and has no refresh token:

```bash
TOKEN=$(python scripts/rozklad_auth.py token --user admin)      # or krentsin, nepyivoda
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/me
```

`AUTH_URL=http://127.0.0.1:8085/auth` reaches the service directly when the web container is not running (the port
is published on 127.0.0.1 only, and `X-Forwarded-For` sent there is ignored). Ten wrong passwords in a row lock a
username for a while (`Забагато спроб…`); a successful sign-in resets the count. A browser that already signed in as
that username keeps its own count (device cookie `RZ_DEVICE`), so others' failures do not lock it.

**Google.** «Увійти через Google» uses the real Google client from `.env` (`AUTH_GOOGLE_CLIENT_ID`,
`AUTH_GOOGLE_CLIENT_SECRET`; setup in `docs/GOOGLE_SIGNIN.md`). Only addresses that have access in «Доступи»
(`/admin/access`) get in; others see «Цієї адреси немає у списку доступу…». Without the client the button is not
shown and the passwords remain. `--google-mock` replaces Google with `mock-oauth2-server` for the automated tests
(`web/e2e-real/google.spec.ts`), which cannot sign in to the real Google; it is not a way to use the stand.

**«Доступи».** The admin page `/admin/access` grants access by e-mail (Google, or a password link the admin
copies from the row menu), changes roles, revokes and restores access. A password link opens `/password/:token`.

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
   2 × ADDITIONAL, 2 × CANCEL of a subgroup, 2 × REPLACE_TEACHER, 9 × REPLACE_DISCIPLINE «Зв.зам.»; in the default
   approval mode NOTIFY they are confirmed at once — in mode CONFIRM Непийвода confirms hers in the cabinet and the
   dispatcher confirms the rest), the 5 notes of the original, publication →
   №35. Printed sheet: `GET /api/documents/orders/{id}.pdf|png|docx`.
8. **names** — first names and patronymics of 89 of the 109 real teachers from `seed/teacher-names.csv`
   (`PUT /api/admin/teachers/{id}` with the version; short and display names stay). Sources are public: the
   cycle commission pages of vtc.vn.ua (Wayback snapshots 2025-2026; the site itself does not answer from
   here) and the «Кадрове забезпечення» PDFs of the OPPs on the college's Google Site. A row is used when the
   initials of the schedule fit, or, for a surname without initials, when one current person has that surname
   and the disciplines match; each row names its source. A name set by hand is kept.

A living stand (by name, not in the default run; the stand time `--time 2026-11-20T10:00` fits the dates):
- **`seed recent`** — every study day from 20.10 to 18.11: 1–3 new absences (sick leave over 2–4 days, personal,
  business trip, other), the system's own plan for each absence accepted (`GET /api/admin/absences/{id}/plan`:
  another discipline of the group by its own teacher «Зв.зам.», merged subgroups, a cancelled pair), and the
  order of the day published (№36…). Deterministic (seeded by the date) and resumable.
- **`python scripts/demo_day.py --publish --allow-chat='<masked id>'`** — then Thursday 19.11 (published) and
  Friday 20.11 with every kind of decision and four lessons left «Потрібне рішення» (the docstring lists them).

The owner's chat (`TELEGRAM_OWNER_CHAT_ID`) is bound as the dispatchers' chat at start-up, so it gets a message
for every absence and every order the seed creates. For a quiet seed run the three commands on a stand started
with `--no-telegram`, then `up` again with the bot.

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

`substitution`: absence of Непийвода → affected lessons → ranked options → change for Кренцін (NOTIFY:
confirmed at once; CONFIRM: pending) → he declines in the cabinet → asked again → confirms → the rest cancelled → dashboard → order №36 → teacher's own schedule →
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
gateway with real sign-ins of the demo users on the page of the sign-in service. It starts its own Vite dev
server on 5180 without mocks (its issuer is `http://localhost:8088/auth`, `web/.env.development`);
`REAL_BASE_URL=http://localhost:8088` runs it against the nginx container instead, `REAL_REUSE_SERVER=1` reuses a
dev server already on 5180. API calls of the tests take tokens the same way as `scripts/rozklad_auth.py`
(`e2e-real/support/tokens.ts`).

`auth.spec.ts` covers sign-in with `returnTo`, sign-out, the wrong-password and lockout messages. The silent renew
test (a hidden iframe with `prompt=none`, no refresh token) waits for a real renewal, so it needs a short token:

```bash
AUTH_ACCESS_TOKEN_TTL=2m docker compose -f infra/docker-compose.yml --profile app up -d --no-build auth
cd web && REAL_SILENT_RENEW=1 npx playwright test -c playwright.real.config.ts auth
# afterwards: scripts/dev-stack.sh up --no-build (back to 15 minutes)
```

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
* 503 «Сервіс входу недоступний» on invites or account links: core cannot reach `auth`
  (`scripts/dev-stack.sh logs auth`; core needs `CORE_AUTH_CLIENT_SECRET` equal on both sides).
* 401 on every API call right after `auth` restarted: in development the signing key lives in memory, so tokens
  issued before the restart are invalid — sign in again. The services fetch the new key by its new `kid` by themselves.
* The importer reports «Не вдалося отримати каталог із сервера розкладу»: core was down during parsing; save mappings
  (`PUT /api/import/jobs/{id}/mappings` with `{"mappings": []}`) to match again.
* `GET /api/documents/orders/{id}.pdf` is 404 for a moment after publication: notification gets the order
  through the `order.published` event.
* Port 5432 belongs to a local PostgreSQL, therefore Docker Postgres is on 5433.
* notification logs «… event(s) in notification.q.events.dlq failed every retry»: some events (an order, a
  substitution, an admin notice) were not handled, usually because notification's database was down for more
  than about a minute. Fix the cause, then, as an ADMIN, `GET /api/notify/events/dead-letters` shows how many and
  `POST /api/notify/events/dead-letters/replay` sends each of them back once (handlers skip events they already
  handled). A message that fails again returns to the DLQ; an unreadable one stays there for a developer
  (RabbitMQ management, `http://127.0.0.1:15672`). The gauge `notification.events.dead_letters` has the count.

## 8. Test coverage

CI sends both reports to SonarCloud (`sonar-project.properties`); the same reports can be built locally.

* **Backend:** `mvn verify` (or `mvn -pl services/core -am verify` for one service). JaCoCo records the unit
  (surefire) and the integration (failsafe, Testcontainers) tests into one `target/jacoco.exec` per module and
  writes the report at `verify`: open `<module>/target/site/jacoco/index.html`; `jacoco.xml` next to it is what
  SonarCloud reads. A module whose tests did not run (`-DskipTests`) has no report.
  On Windows the JaCoCo agent cannot write to a path with non-ASCII characters (a Cyrillic folder name): Maven
  then prints «Skipping JaCoCo execution due to missing execution data file». Point the data file at an ASCII
  path: `J='C:/Temp/jacoco/${project.artifactId}.exec'; mvn verify -Djacoco.destFile="$J" -Djacoco.dataFile="$J"`.
* **Web:** `cd web && npm run test:coverage`: the same tests as `npm test` plus a summary in the console,
  `web/coverage/lcov-report/index.html` and `web/coverage/lcov.info` (for SonarCloud). Tests, the mock API
  (`src/mocks`), test helpers and the dev kitchen sink are not counted.
