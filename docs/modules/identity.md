# Module `identity` (core)

Current user, teacher ↔ Keycloak account link, registration by invite, internal teacher lookup.

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
| `POST /api/public/invites/{token}/accept {username, password}` | 201 `{username}`; 400 validation; 404; 409 username taken; 410 used/expired/revoked (`status` in the body); 503 Keycloak unavailable |
| `GET /api/internal/teachers/{id}` (SERVICE) | teacher with `keycloakUserId`, `linked`, cycle commission |

## Invite flow

1. Admin creates an invite: 32 random bytes → URL-safe Base64 token (43 chars); only its SHA-256 hex is
   stored; expiry `app.identity.invite-ttl` (default 7 days); older open invites of the teacher are
   revoked (column `revoked_at`, migration `V1_801`), so only the newest link works. Inactive teacher → 422.
2. The SPA page `/invite/{token}` shows `teacherName` (full name when known).
3. Accept locks the invite row (`SELECT … FOR UPDATE`), checks the status (410 otherwise), lower-cases
   the username (3–50 Latin letters, digits, `.`, `_`, `-`), requires a password of 8–128 characters
   that is not the username.
   - Teacher without an account → create an enabled Keycloak user, set the password, grant the realm
     role TEACHER, link `teacher.keycloak_user_id`. If the transaction does not commit, the new user is
     deleted again (transaction synchronization); a partly created user (password rejected) is deleted
     at once.
   - Teacher already linked → the same username restores access (enable + new password + TEACHER);
     another username → 409 naming the existing one. A link to a user deleted in Keycloak creates a
     new account.
   - Success marks the invite used and revokes the teacher's other open invites.
4. Keycloak calls go through `IdentityProvider` (`KeycloakIdentityProvider`: keycloak-admin-client,
   client `rozklad-core-admin`, client credentials, 5 s connect / 15 s read timeouts, created lazily —
   core starts without Keycloak or the secret; a missing `CORE_ADMIN_CLIENT_SECRET` is a 503 on accept).
   The role is looked up with `role-mappings/realm/available`, which `view-users` allows, so the service
   account needs no `view-realm`. The email is not sent to Keycloak (duplicate e-mails would look like a
   taken username).

## Demo seed (profile `demo`)

`DemoAccountLinks` links the realm users `krentsin` / `nepyivoda` (fixed ids `…0002` / `…0003`) to
the imported teachers «Кренцін» / «Непийвода» at startup and after each catalog import — only when
exactly one unlinked teacher has the surname and the account is not linked yet.

## How to test

`InviteTokensTest`, `InviteServiceTest` (all branches with `FakeIdentityProvider`), `DemoAccountLinksTest`,
`PublicInviteControllerTest`, `MeControllerTest`, `InviteFlowIT` (PostgreSQL; account creation with
the fake provider inside a real transaction, HTTP for 404/410/revocation/`/api/me`).

Manual check against the running Keycloak: start core with `CORE_ADMIN_CLIENT_SECRET` set, create an
invite as admin, accept it, log in as the new user in the SPA, then delete the user in the admin console.

Manual check on 2026-11-08 (core jar with profile `demo`, own database, local Keycloak 26.8.2): invite
creation, `GET` of the invite and the demo account link worked. Accept answered 503
`identity-unavailable` and the invite stayed usable, because Keycloak answered 401 to every admin REST
call made with a `rozklad` realm token — even though the token of `rozklad-core-admin` was issued,
carried `aud: realm-management` with `manage-users`/`view-users`/`query-users`, and the service
account user was enabled; the same call with a `master` admin token succeeded. So account creation,
login of the new user and the 410 on a second accept were not verified live. The repository `.env`
has no `CORE_ADMIN_CLIENT_SECRET`; the check used the dev value from `.env.example`, which the realm
import also uses.

## Known gaps

- The live Keycloak path is not covered by automated tests (no Keycloak container in the test suite)
  and failed in the manual check above with 401 from the Keycloak admin API (environment, see above).
- No endpoint to unlink an account or to delete invites; old invites stay as history.
