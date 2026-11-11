# Notes for coding agents

Rozklad VTFK: timetable, substitutions and orders of a Ukrainian college, with a Telegram bot. Monorepo:
a Maven reactor (`libs/contracts`, `services/{core,scheduler,importer,notification,gateway}`) and a separate
React app in `web/`. Start with `README.md`, then `docs/ARCHITECTURE.md`, `docs/DOMAIN.md` and the module
doc in `docs/modules/` for the area you touch.

## Commands

```bash
source scripts/env.sh                         # JDK 25 + Maven (ROZKLAD_JAVA_HOME / ROZKLAD_MAVEN_BIN override)
mvn -pl services/core -am verify              # one service with its dependencies; *Test = surefire, *IT = failsafe
mvn -pl services/core verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='SomeIT#method'
mvn verify                                    # everything (Testcontainers: Docker must run)
cd web && npm run lint && npm test && npm run build && npm run e2e
scripts/dev-stack.sh up --web [--no-telegram] # the whole stack in Docker; status | logs <svc> | down [--all]
```

On Windows use `http://127.0.0.1:8080` for the gateway: `localhost` may resolve to `::1`, where WSL can hold the port.

## Rules that matter here

- **Language.** Code, identifiers, commit messages and docs are in English. UI text, bot messages and
  user-facing errors are in Ukrainian.
- **Contracts.** Cross-service events live in `libs/contracts`, carry `eventId`, `occurredAt` and `version`,
  and change only backward-compatibly. Record every change in `docs/CHANGELOG-CONTRACTS.md` and `docs/CONTRACTS.md`.
- **Boundaries.** `core` is a Spring Modulith monolith. Other modules use only a module's top-level package
  (its API); `internal` packages are private. `ModularityTests` and `ArchitectureRulesTest` enforce this.
- **Database.** Every change goes through a new Flyway migration. Never edit an applied one. Each service owns its database.
- **Performance budgets.** Some ITs count SQL statements, for example `OptionsGatheringIT`. Do not raise a
  budget to make a test pass: find the extra query.
- **Domain truth.** Domain rules come from the college's real workflow (`docs/DOMAIN.md`). Do not invent
  rules. Open questions are listed there.
- **Secrets.** Real values live only in `.env`, which is gitignored. Never print, log or commit them. Add
  new variables to `.env.example` with a placeholder.
- **Telegram.** One bot token can be polled by one process only. Do not message real chats from tests.
- **Tests.** Every behaviour change gets a test at the lowest useful level, error paths included. Web tests sit
  next to the code (`*.test.ts(x)`); Playwright specs are in `web/e2e` and `web/e2e-real`.
