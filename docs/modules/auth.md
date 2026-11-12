# Service `auth` (sign-in)

The college's own OIDC provider: Spring Authorization Server (Spring Security 7) in `services/auth`, with a
Ukrainian sign-in page on the site's own origin. It owns accounts, passwords, Google bindings, sign-in
sessions, OAuth clients, the token signing key and lockout. `core.identity` keeps invites, password links,
the teacher ↔ account link and the rules of access grants («Доступи»), and calls `auth` through its internal
API (`docs/modules/identity.md`).

## Where it runs

| | Development | Server |
|---|---|---|
| Issuer (`AUTH_ISSUER`) | `http://localhost:8088/auth` (nginx of the `web` container) | `https://<DOMAIN>/auth` (Caddy) |
| Process | port 8085, context path `/auth`; actuator on 8086 (health only, never proxied) | same, not published |
| Database | `auth` in the shared PostgreSQL (Flyway `V1_0xx` accounts, `V1_1xx` OAuth and sessions) | same |
| JWKS / token URI for the services | `http://auth:8085/auth/oauth2/jwks`, `…/oauth2/token` | same |

The proxies pass `/auth/**` unchanged and close `/auth/internal/**` (404). `auth` builds its redirects from
`X-Forwarded-*` (`server.forward-headers-strategy=native`). Discovery:
`<issuer>/.well-known/openid-configuration`.

## Clients

| Client | Type | Use |
|---|---|---|
| `rozklad-web` | public, code + PKCE S256 | the SPA; redirect URIs `<origin>/login/callback` and `<origin>/login/silent.html`, post-logout `<origin>/` for every origin of `AUTH_WEB_ORIGINS` |
| `rozklad-cli` | public, code + PKCE, loopback redirect `http://127.0.0.1:<port>/cli-callback` | `scripts/rozklad_auth.py` and `web/e2e-real`; exists only with `AUTH_CLI_ENABLED=true` (development) |
| `rozklad-notification`, `rozklad-importer` | `client_credentials` | service tokens with the roles `SERVICE` + `SERVICE_NOTIFICATION` / `SERVICE_IMPORTER` (core checks the own one) |
| `rozklad-core` | `client_credentials`, scope `accounts.manage` | the internal accounts API |

Clients are written to the database at every start from `app.auth.*` (`RegisteredClientSynchronizer`); a
service client with an empty secret is not registered, and a client that left the configuration is removed.
There is no password grant and no refresh token for the SPA.

## Tokens

- **Access token** (JWT, RS256, `kid` of the signing key): `iss`, `sub` = account id (UUID; for services the
  client id), `aud` = `[<client>, rozklad-api]`, `roles` (flat list: `ADMIN`, `TEACHER`, `SERVICE`, `SERVICE_NOTIFICATION`, `SERVICE_IMPORTER`),
  `preferred_username` (username, else e-mail; services: `service-account-<client>`), `scope`. Lifetimes:
  SPA 15 min in development, 5 min on the server (`AUTH_ACCESS_TOKEN_TTL`); services 5 min.
- **ID token:** `sub`, `preferred_username`, `name`, `email`, `sid`; no roles.
- Roles are read from the database at every issuance, so a role change or a revocation applies within one
  access-token lifetime; a disabled account gets `invalid_grant`.
- Resource servers (core, importer, notification, scheduler) check `exp`/`nbf`, `iss` = `AUTH_ISSUER` and
  `aud` ∋ `AUTH_AUDIENCE` (`rozklad-api`), and map `roles` to `ROLE_<NAME>` (`TokenRoles`). They fetch the
  JWKS again when a token carries an unknown `kid`.
- **Renewal.** The SPA renews in a hidden iframe (`/login/silent.html`, `prompt=none`) while the sign-in
  session lives; without it `auth` answers `login_required` and the SPA goes to the sign-in page.

## Sign-in

- **Password.** `/auth/login` (Thymeleaf, `lang="uk"`, no inline scripts, CSP with `form-action` limited to
  the redirect origins). BCrypt cost 12; one generic error for every failure; the account state is checked
  after the password, so a disabled account is revealed only to someone who knows its password.
- **Policy** (`PasswordPolicy`): at least `AUTH_PASSWORD_MIN_LENGTH` (12) characters, at most 64 characters
  and 72 UTF-8 bytes, not the username or e-mail, not in the bundled list of common passwords.
- **Lockout** (`LoginThrottleFilter`, `LockoutPolicy`): per identifier and network (default 10 failures in
  12 h, 5 on the server); a throttle for a network that tries many identifiers (20 in 15 min); after 50
  failures of one identifier in 24 h from all networks a global gate (`login_gate`) lets one password check
  through per 2 s (longer as failures grow, at most 15 min) and refuses the rest without BCrypt, parallel
  requests included. A browser that already signed in as the identifier carries a device cookie
  (`RZ_DEVICE`, on the server `__Secure-RZ_DEVICE`; `HttpOnly`, `SameSite=Strict`, path `/auth/login`,
  90 days, table `login_device`): its attempts have their own lockout key and skip the network throttle and
  the gate, so a student spraying from the college NAT cannot lock teachers out. Every attempt is in
  `login_attempt`. The client IP comes from `X-Forwarded-For` only from `AUTH_TRUSTED_PROXIES` (loopback by
  default; the compose network's container range in compose).
- **Google** (`docs/GOOGLE_SIGNIN.md`): only verified addresses that have access in «Доступи»; an address on
  the college domain must come with the matching `hd`; a binding is kept by Google's `sub`. A refusal shows
  «Цієї адреси немає у списку доступу. Зверніться до навчальної частини.» Development uses a mock provider
  (`scripts/dev-stack.sh up --google-mock`).
- **Sessions** are Spring Session JDBC (cookie `RZ_AUTH`, on the server `__Secure-RZ_AUTH`, path `/auth`,
  `HttpOnly`, `SameSite=Lax`): idle 8 h / absolute 24 h in development, 1 h / 10 h on the server. A deploy
  does not sign anybody out. Logout (`/auth/connect/logout`) ends the session and deletes the authorization
  of the presented ID token.

## Accounts

- `account` (id = `sub`, username and e-mail unique in lower case, display name, BCrypt hash or none,
  `enabled`, `disabled_reason` `REVOKED`/`DEMO_OFF`, `demo`), `account_role`, `federated_identity`.
- **Revocation is sticky:** a revoked account comes back only through «Повернути доступ»
  (`POST …/enable` with `clearRevocation=true`); invites and password links never enable it. Revoke deletes
  the account's sessions and authorizations; so does a new password (`PUT …/password`, the bootstrap repair).
- **Usable admin:** an enabled ADMIN with a password or a Google binding. A change that would leave no usable
  admin is refused (`last-admin`).
- **Internal API** (`/auth/internal/accounts`, only a token with `sub=rozklad-core` and scope
  `accounts.manage`): `GET /{id}`, `GET ?username=|email=|ids=|q=&role=&enabled=` (search, pages), `PUT /{id}` (idempotent create with the id core
  chose), `PATCH /{id}`, `PUT /{id}/password`, `POST /{id}/revoke`, `POST /{id}/enable`,
  `DELETE /{id}/federated/google`, `DELETE /{id}`. Errors are RFC 9457 problems with stable types
  (`username-taken`, `email-taken`, `last-admin`, `account-revoked`, `password-rejected` with `reason`).
  `X-Actor` names the admin for the audit columns.

## Seeds and the first admin

| What | When | Accounts |
|---|---|---|
| `DemoSeed` | development only (`AUTH_DEMO_SEED=true`, http issuer) | `admin`, `krentsin`, `nepyivoda` with the fixed ids `a0000000-0000-4000-8000-000000000001/2/3` and the passwords of `infra/dev/demo-users.env`; existing rows are not touched |
| `ServerDemoAccounts` | https issuer, every start | `krentsin`, `nepyivoda` (TEACHER, `demo=true`) with `DEMO_TEACHER_PASSWORD`; empty password = they are disabled with `DEMO_OFF` and signed out; a revoked or non-demo account is left alone |
| `BootstrapAdmin` | every start, only while no usable admin exists | `AUTH_BOOTSTRAP_ADMIN_USERNAME`/`_EMAIL` + `AUTH_BOOTSTRAP_ADMIN_PASSWORD`: creates the admin or repairs the matching account; `AUTH_BOOTSTRAP_FORCE=true` repairs it even when other admins exist |

The readiness group includes `adminCredential`: `auth` is not ready until a usable admin exists.

## Signing key

`AUTH_SIGNING_KEY_FILE` (PKCS#8 PEM, RSA 2048) with `AUTH_SIGNING_KEY_ID` as `kid`. Without a file and with an
http issuer, a key is generated in memory at every start (development; the services refetch the JWKS by the
new `kid`). An optional previous public key (`AUTH_PREVIOUS_PUBLIC_KEY_FILE`, `AUTH_PREVIOUS_KEY_ID`) is
published during a rotation. Server runbook: `docs/DEPLOY.md`.

## Production guards (`ProdGuard`)

With an https issuer `auth` refuses to start when: there is no signing key file; the session cookie is not
`Secure`; `PUBLIC_WEB_URL`, a web origin or a CORS origin is http or localhost; the development demo seed or
the Google mock is on; Google endpoints are overridden; Google is on without a hosted domain; a client secret
is a known development value.

## Tests

Unit: `PasswordPolicyTest`, `LockoutPolicyTest`, `TokenClaimsCustomizerTest`, `SigningKeyLoaderTest`,
`CspHeaderTest`, `ProdGuardTest`, `ClientRegistrationsTest`, `SilentAuthorizeRequestTest`, `GoogleUnitsTest`.
ITs (Testcontainers PostgreSQL, real HTTP): `DiscoveryIT`, `AuthorizationCodeFlowIT`, `PasswordLoginIT`,
`LockoutIT`, `ClientCredentialsIT`, `JdbcAuthorizationRoundTripIT`, `LogoutIT`, `SessionIT`, `LoginPageIT`,
`GoogleLoginIT` (in-process mock provider), `InternalAccountsApiIT`, `LastAdminIT`, `AccountSchemaIT`,
`ServerDemoAccountsIT`. The resource servers have `TokenIssuerTest` (unknown `kid`, wrong issuer, missing
audience). `web/e2e-real/auth.spec.ts` and `google.spec.ts` drive the real page.
