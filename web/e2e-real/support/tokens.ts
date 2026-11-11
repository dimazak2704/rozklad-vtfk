import { createHash, randomBytes } from 'node:crypto';

/**
 * Access token демо-користувача для API-запитів e2e-real. Парольного grant-а сервіс входу не має, тож
 * проходимо справжній вхід по HTTP, як scripts/rozklad_auth.py: публічний клієнт `rozklad-cli`
 * (є лише з AUTH_CLI_ENABLED=true), код + PKCE, повернення на loopback-адресу, яку ніхто не відкриває.
 */

export const AUTH_URL = (process.env.REAL_AUTH_URL || 'http://localhost:8088/auth').replace(/\/$/, '');
const CLIENT_ID = 'rozklad-cli';
const REDIRECT_URI = 'http://127.0.0.1:53682/cli-callback';

const base64url = (bytes: Buffer) => bytes.toString('base64url');

/** Мінімальний кошик cookie однієї сесії входу (cookie сервісу входу має Path=/auth). */
class CookieJar {
  private readonly cookies = new Map<string, string>();

  remember(response: Response): void {
    for (const header of response.headers.getSetCookie()) {
      const [pair] = header.split(';');
      const at = pair?.indexOf('=') ?? -1;
      if (pair && at > 0) this.cookies.set(pair.slice(0, at).trim(), pair.slice(at + 1).trim());
    }
  }

  header(): string {
    return [...this.cookies].map(([name, value]) => `${name}=${value}`).join('; ');
  }
}

/** Іде за переадресаціями, як браузер (після POST — GET), і зупиняється на loopback-адресі повернення. */
async function follow(jar: CookieJar, url: string, init: RequestInit = {}): Promise<{ callback: URL | null; body: string }> {
  let current = url;
  let request: RequestInit = init;
  for (let hop = 0; hop < 10; hop++) {
    const response = await fetch(current, {
      ...request,
      redirect: 'manual',
      headers: { Accept: 'text/html', Cookie: jar.header(), ...(request.headers as Record<string, string> | undefined) },
    });
    jar.remember(response);
    const location = response.headers.get('location');
    if (response.status < 300 || response.status >= 400 || !location) {
      return { callback: null, body: await response.text() };
    }
    const next = new URL(location, current);
    if (next.href.startsWith(REDIRECT_URI)) return { callback: next, body: '' };
    current = next.href;
    request = {};
  }
  throw new Error(`забагато переадресацій під час входу (${current})`);
}

export interface Tokens {
  accessToken: string;
  expiresIn: number;
}

/** Вхід через сторінку входу і обмін коду на токени. Помилка — з текстом сторінки входу. */
export async function signInForToken(username: string, password: string): Promise<Tokens> {
  const verifier = base64url(randomBytes(32));
  const challenge = base64url(createHash('sha256').update(verifier).digest());
  const state = base64url(randomBytes(16));
  const jar = new CookieJar();
  const query = new URLSearchParams({
    response_type: 'code',
    client_id: CLIENT_ID,
    redirect_uri: REDIRECT_URI,
    scope: 'openid',
    state,
    code_challenge: challenge,
    code_challenge_method: 'S256',
  });
  const page = await follow(jar, `${AUTH_URL}/oauth2/authorize?${query}`);
  const csrf = /name="_csrf"[^>]*value="([^"]+)"|value="([^"]+)"[^>]*name="_csrf"/.exec(page.body);
  const token = csrf?.[1] ?? csrf?.[2];
  if (!page.callback && !token) throw new Error(`${AUTH_URL}/login без форми входу (AUTH_CLI_ENABLED=true?)`);
  const answer = page.callback
    ? page
    : await follow(jar, `${AUTH_URL}/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ username, password, _csrf: token ?? '' }).toString(),
      });
  if (!answer.callback) {
    const alert = /role="alert"[\s\S]*?<span>([^<]+)<\/span>/.exec(answer.body)?.[1];
    throw new Error(`вхід «${username}» не вдався: ${alert ?? 'сторінка входу без пояснення'}`);
  }
  if (answer.callback.searchParams.get('state') !== state) throw new Error('повернення входу з чужим state');
  const code = answer.callback.searchParams.get('code');
  if (!code) throw new Error(`сервіс входу відмовив: ${answer.callback.searchParams.get('error')}`);
  const response = await fetch(`${AUTH_URL}/oauth2/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
    body: new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: CLIENT_ID,
      code,
      redirect_uri: REDIRECT_URI,
      code_verifier: verifier,
    }).toString(),
  });
  if (!response.ok) throw new Error(`обмін коду на токен: ${response.status} ${await response.text()}`);
  const json = (await response.json()) as { access_token: string; expires_in: number };
  return { accessToken: json.access_token, expiresIn: json.expires_in };
}
