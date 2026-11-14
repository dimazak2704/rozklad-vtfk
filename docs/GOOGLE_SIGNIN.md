# Sign-in with Google

Teachers and dispatchers can sign in with their college Google account instead of a password. Google only
proves who the person is; whether they may enter is decided by our access list, the page «Доступи»
(`/admin/access`). An address that is not there gets «Цієї адреси немає у списку доступу. Зверніться до
навчальної частини.», even if it is on the college domain. So a student with a `@vtc.vn.ua` address cannot
sign in unless an admin added that address.

This page is for the owner, who creates the Google client. Nobody else handles its secret. How the sign-in
works inside `auth` is in `docs/modules/identity.md` and the design notes (DESIGN §4.4).

## What you need

- A Google account that can create projects in the college's Google Workspace organisation (`vtc.vn.ua`).
  If you cannot, ask the Workspace administrator to create the project or give you the role
  «Project Creator» in the organisation.
- The domain the site runs on in production, `<DOMAIN>` below (the same value as `DOMAIN` in
  `infra/deploy/.env`).

## Step by step

1. Open <https://console.cloud.google.com/> signed in with your college account and pick the organisation
   `vtc.vn.ua` in the project selector.
2. **Create a project.** «New project», name `rozklad-vtfk`, location: the organisation `vtc.vn.ua`.
3. **Consent screen.** «Google Auth Platform» → «Branding» (in older consoles «OAuth consent screen»):
   - User type: **Internal**. Only accounts of the college Workspace can then sign in at all; this is a
     second barrier besides our domain check.
   - App name «Розклад ВТФК», support e-mail: yours; developer contact: yours.
   - Scopes: nothing to add. We use only `openid`, `email` and `profile`, which need no review.
4. **Create the client.** «Clients» (or «Credentials» → «Create credentials» → «OAuth client ID»):
   - Application type: **Web application**, name `rozklad-auth`.
   - Authorized JavaScript origins: **none** (the browser never calls Google from JavaScript).
   - Authorized redirect URIs, exactly, one per line:

     | Where | Redirect URI |
     |---|---|
     | Production | `https://<DOMAIN>/auth/login/oauth2/code/google` |
     | Local stand (optional, to try the real Google on your computer) | `http://localhost:8088/auth/login/oauth2/code/google` |

     The address must match to the character: scheme, host, port, path, no trailing slash. `auth` always sends
     `<issuer>/login/oauth2/code/google`, where the issuer is `AUTH_ISSUER` (`https://<DOMAIN>/auth` in
     production, `http://localhost:8088/auth` locally). Google allows `http` only for `localhost`.
   - Save. Copy the **Client ID** and the **Client secret**. The secret is shown in full only now; if you lose
     it, add a new secret in the client and delete the old one.
5. **Production settings.** Put the values into `infra/deploy/.env` on the server (never into git, chat or
   tickets):

   ```env
   AUTH_GOOGLE_CLIENT_ID=<client id>.apps.googleusercontent.com
   AUTH_GOOGLE_CLIENT_SECRET=<client secret>
   AUTH_GOOGLE_HOSTED_DOMAIN=vtc.vn.ua
   ```

   Redeploy (or restart the `auth` container). `auth` refuses to start in production when Google is on without
   `AUTH_GOOGLE_HOSTED_DOMAIN`, or when the mock settings below are set.
6. **Check.** Open `https://<DOMAIN>/login`: the sign-in page shows «Увійти через Google». Add your address in
   «Доступи» first if it is not there, then sign in with Google.

### Try the real Google locally (optional)

Add the localhost redirect URI from step 4, then put the same three variables into the root `.env` of the
repository and restart the stand with `scripts/dev-stack.sh up --no-build`. The button appears on
`http://localhost:8088/auth/login`. Remove the variables when you are done.

## Who may sign in

| Google account | Result |
|---|---|
| `name@vtc.vn.ua` of the college Workspace, address in «Доступи», access not revoked | signs in |
| the same, address not in «Доступи» (for example a student) | «Цієї адреси немає у списку доступу…» |
| a private Google account registered on a `@vtc.vn.ua` address (Google sends no `hd`) | refused |
| an address on another domain (`@gmail.com`) | refused (unless `AUTH_GOOGLE_ALLOW_CONSUMER_ACCOUNTS=true`, not recommended) |
| e-mail not verified at Google | «Не вдалося увійти через Google. Спробуйте ще раз.» |
| the access was revoked in «Доступи» | refused; only «Повернути доступ» restores it |

The first Google sign-in binds the account to Google's permanent account id. After that the person keeps
signing in even if the address is renamed in Workspace. If the Workspace account is deleted and created again
with the same address, Google gives it a new id and the sign-in is refused; an admin presses «Відв'язати
Google» in «Доступи», and the next sign-in binds the new account. When staff leave, revoke their access in
«Доступи»: that also covers an address that is later given to someone else.

## Automated tests without Google

Browser tests cannot sign in to the real Google (bot detection, two-factor prompts), so `web/e2e-real/google.spec.ts`
runs against a fake one: `scripts/dev-stack.sh up --google-mock` starts `mock-oauth2-server`
(`infra/docker-compose.google-mock.yml`) in place of Google. For using the stand, configure the real client above.
With the mock, «Увійти через Google» opens its page on `http://localhost:8090/google`: type any user name (it becomes the Google id) and the claims, for example

```json
{"email": "krentsin@rozklad.local"}
```

for the demo teacher. Without a configured domain any address passes the domain rule, so the access list is
what you test. To test the domain rule, start with `GOOGLE_MOCK_HOSTED_DOMAIN=vtc.vn.ua scripts/dev-stack.sh up
--google-mock` and type `{"email": "x@vtc.vn.ua", "hd": "vtc.vn.ua"}` (and without `hd` to see the refusal).
The mock always sends `email_verified: true` (`infra/dev/mock-oidc.json`).

## Not verified yet

- Whether Google accepts a `sslip.io` host (for example `1-2-3-4.sslip.io`) as a redirect URI. Google requires
  a public top-level domain and rejects raw IP addresses; if the console refuses the address, use a real
  domain for `DOMAIN`.
