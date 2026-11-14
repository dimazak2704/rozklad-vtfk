# Module `identity` (core)

Current user, teacher ↔ account link, registration by invite, internal teacher lookup. Accounts live in the
sign-in service `services/auth`; core manages them through its internal accounts API.

## API for other modules (base package `ua.vtc.rozklad.core.identity`)

`CurrentUserService`: `require()` (401 when anonymous), `find()`, `profile()` (`UserProfile {userId,
username, roles, teacher?}`), `currentTeacher()`, `requireTeacherId()` (403 «Ваш обліковий запис не
прив'язано до викладача» when the account has no teacher) — use it for ownership checks.

## Endpoints

| Endpoint | Notes |
|---|---|
| `GET /api/me` (TEACHER, ADMIN) | `{userId, username, roles, teacher:{id, shortName, displayName}?}` |
| `POST /api/admin/invites {teacherId}` | 201 `{id, token, url, expiresAt, teacher}` — the token is shown once |
| `GET /api/admin/invites?teacherId=` | `[{id, teacher, status: ACTIVE\|USED\|EXPIRED\|REVOKED, expiresAt, usedAt, revokedAt, createdAt, createdBy}]` |
| `GET /api/public/invites/{token}` | `{valid, status, teacherName, expiresAt}`; 404 for an unknown token |
| `POST /api/public/invites/{token}/accept {username, password}` | 201 `{username}`; 400 validation (password 12–64 characters; `auth` may refuse it with a reason, shown in Ukrainian); 404; 409 username taken; 410 used/expired/revoked invite, or a revoked account («Доступ відкликано…», `inviteStatus: REVOKED`); 422 `INVITE_NOT_FOR_ADMIN` (a teacher invite never changes an account with the role ADMIN); 503 sign-in service unavailable |
| `GET /api/internal/teachers/{id}` (SERVICE) | teacher with `accountId`, `linked`, cycle commission |
| `GET /api/admin/teachers/{id}/account` | `{teacherId, teacherName, linked, userId, username, enabled}` (`username` is the username or, for an access grant by e-mail, the e-mail; null when `auth` lost the account) |
| `PUT /api/admin/teachers/{id}/account {username}` | links an **existing** account by username or e-mail (`TeacherAccountService`): grants TEACHER, revokes open invites; 404 teacher, 422 `ACCOUNT_NOT_FOUND`/`ACCOUNT_DISABLED`, 409 account of another teacher or the teacher already has a live account, 503 sign-in service down. `scripts/seed_demo.py` links `krentsin`/`nepyivoda` this way |
| `DELETE /api/admin/teachers/{id}/account` | 204, idempotent; the account in `auth` stays |


## Invite flow

1. Admin creates an invite: 32 random bytes → URL-safe Base64 token (43 chars); only its SHA-256 hex is
   stored; expiry `app.identity.invite-ttl` (default 7 days); older open invites of the teacher are
   revoked (column `revoked_at`, migration `V1_801`), so only the newest link works. Inactive teacher → 422.
2. The SPA page `/invite/{token}` shows `teacherName` (full name when known).
3. Accept locks the invite row (`SELECT … FOR UPDATE`), checks the status (410 otherwise), lower-cases
   the username (3–50 Latin letters, digits, `.`, `_`, `-`, starting with a letter), requires a password
   of 12–64 characters that is not the username. `auth` applies its full policy (at most 72 UTF-8 bytes,
   not the username or e-mail, not a common password) and core shows the reason in Ukrainian.
   - Teacher without an account → core picks the account id and creates the account (`PUT`, idempotent)
     with the role TEACHER, the password and the teacher's full name, then links `teacher.account_id`.
     The compensation (delete the account if the transaction does not commit) is registered **before**
     the call, so an account `auth` created while the answer was lost is removed too.
   - Teacher linked to an account without a username (access granted by e-mail) → the chosen username
     is set, then password + enable + TEACHER.
   - Teacher already linked → the same username restores access (new password + enable + TEACHER);
     another username → 409 naming the existing one. A link to an account deleted in `auth` creates a
     new account.
   - Linked account with the role **ADMIN** → 422 `INVITE_NOT_FOR_ADMIN`, checked first, so neither the
     password nor the username changes and the answer does not name the account. An invite lives a week and
     is easy to forward; an admin's password is set only by a password link (below).
   - Linked account **revoked** (DESIGN D11) → 410 «Доступ відкликано. Зверніться до навчальної частини.»
     with `inviteStatus: REVOKED`; the invite is revoked and that is committed. `auth` refuses to enable a
     revoked account without an explicit restore, so a revocation racing the accept still wins.
   - Success marks the invite used and revokes the teacher's other open invites.
4. Calls to `auth` go through `IdentityProvider` (`AuthServiceIdentityProvider`: `RestClient` on
   `app.identity.accounts-api.url` = `AUTH_INTERNAL_URL`, 5 s connect / 15 s read timeouts, header
   `X-Actor` with the admin's `preferred_username` or `rozklad-core`). The token comes from the
   registration `rozklad-auth-admin` (client `rozklad-core`, `client_credentials`, scope `accounts.manage`,
   `AUTH_TOKEN_URI`, secret `CORE_AUTH_CLIENT_SECRET`) on the first call, so core starts while `auth` is
   down. Problems of `auth` map to the port: 409 `username-taken`/`email-taken`/`last-admin`/
   `account-revoked`, 400 `password-rejected` with `reason`, 404; anything else, timeouts and token
   failures are `IdentityProviderException` (503). A timed-out create is retried once with the same id.

## Password links

«Посилання для пароля» in «Доступи» (`AccessService.passwordLink` → `InviteService.createPasswordLink`) uses
the same table and token rules, bound to an account instead of a teacher, and opens `/password/{token}`. It
sets the password of any account, an admin's too, so it lives `app.identity.password-link-ttl`
(`PASSWORD_LINK_TTL`, default 24 hours), shorter than an invite. A newer link revokes the older ones; a revoked
account gets none.

## Logs

The tokens are bearer credentials. `ApiExceptionHandler` writes `/api/public/invites/***/…` instead of the
token when it logs a request path; nginx masks the SPA pages `/invite/` and `/password/` in its access log.

## Demo seed (profile `demo`)

`DemoAccountLinks` links the demo accounts `krentsin` / `nepyivoda` (fixed ids `…0002` / `…0003`, the same
in the dev seed of `auth`) to
the imported teachers «Кренцін» / «Непийвода» at startup and after each catalog import — only when
exactly one unlinked teacher has the surname and the account is not linked yet.

## How to test

`InviteTokensTest`, `InviteServiceTest` (all branches with `FakeIdentityProvider`, which keeps the
revocation rule of `auth`), `AuthServiceIdentityProviderTest` (the HTTP contract with `MockRestServiceServer`:
201/200, 409s, 400 reasons, 404, 5xx, 403, timeout and retry, `X-Actor`), `DemoAccountLinksTest`,
`PublicInviteControllerTest`, `MeControllerTest`, `InviteFlowIT` (PostgreSQL; account creation with
the fake provider inside a real transaction, HTTP for 404/410/revocation/`/api/me`).

The other side of the contract is tested in `auth`: `InternalAccountsApiIT` (only `rozklad-core` with
`accounts.manage` may call; idempotent `PUT`; the 409s and password reasons; revoke ends sessions and
authorizations; `account-revoked`) and `LastAdminIT` (the usable-admin rule).

## Known gaps

- No end-to-end test runs core against a live `auth`; the contract is covered from both sides (see above).
- No endpoint to unlink an account or to delete invites; old invites stay as history.
