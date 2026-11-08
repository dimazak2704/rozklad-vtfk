import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { expect, request, type APIRequestContext, type Browser, type Page } from '@playwright/test';

/**
 * Спільне для e2e на справжньому стенді (playwright.real.config.ts): облікові записи, вхід через Keycloak
 * і запити до API від імені диспетчера — щоб тест знав, що саме має показати сторінка.
 */

export const GATEWAY = process.env.REAL_GATEWAY_URL || 'http://localhost:8080';
export const KEYCLOAK = process.env.REAL_KEYCLOAK_URL || 'http://localhost:8180';
const REALM = `${KEYCLOAK}/realms/rozklad`;

export type Role = 'ADMIN' | 'TEACHER' | 'TEACHER2';

/** Тестові облікові записи локального стенду — з того самого файла, що читають seed і dev-stack.sh. */
function readDemoUsers(): Record<string, string> {
  const file = fileURLToPath(new URL('../../../infra/keycloak/demo-users.env', import.meta.url));
  const entries = readFileSync(file, 'utf8')
    .split(/\r?\n/)
    .filter((line) => /^[A-Z0-9_]+=/.test(line))
    .map((line) => {
      const at = line.indexOf('=');
      return [line.slice(0, at), line.slice(at + 1).trim()] as const;
    });
  return Object.fromEntries(entries);
}

const users = readDemoUsers();

export function credentials(role: Role): { username: string; password: string } {
  const username = users[`${role}_USERNAME`];
  const password = users[`${role}_PASSWORD`];
  if (!username || !password) throw new Error(`У infra/keycloak/demo-users.env немає ${role}_USERNAME/${role}_PASSWORD`);
  return { username, password };
}

/** Вхід через справжню сторінку Keycloak (PKCE) і повернення в SPA. */
export async function login(page: Page, role: Role, returnTo?: string): Promise<void> {
  const { username, password } = credentials(role);
  await page.goto(returnTo ? `/login?returnTo=${encodeURIComponent(returnTo)}` : '/login');
  await page.waitForURL((url) => url.href.startsWith(KEYCLOAK), { timeout: 30_000 });
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();
  await page.waitForURL((url) => !url.href.startsWith(KEYCLOAK) && !url.pathname.startsWith('/auth/') && url.pathname !== '/login', {
    timeout: 30_000,
  });
}

/** Окрема вкладка (свій контекст) з іншим користувачем: диспетчер і викладач працюють одночасно. */
export async function pageAs(browser: Browser, role: Role, baseURL: string, returnTo?: string): Promise<Page> {
  const context = await browser.newContext({ baseURL, locale: 'uk-UA', timezoneId: 'Europe/Kyiv', viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  await login(page, role, returnTo);
  return page;
}

async function token(role: Role): Promise<string> {
  const { username, password } = credentials(role);
  const api = await request.newContext();
  try {
    const response = await api.post(`${REALM}/protocol/openid-connect/token`, {
      form: { client_id: 'rozklad-dev-cli', grant_type: 'password', username, password },
    });
    expect(response.ok(), `токен ${role}: ${response.status()}`).toBe(true);
    return ((await response.json()) as { access_token: string }).access_token;
  } finally {
    await api.dispose();
  }
}

/** API через gateway від імені ролі (для підготовки й перевірок; сам сценарій іде через інтерфейс). */
export async function apiAs(role: Role): Promise<APIRequestContext> {
  return request.newContext({ baseURL: GATEWAY, extraHTTPHeaders: { Authorization: `Bearer ${await token(role)}` } });
}

export async function getJson<T>(api: APIRequestContext, path: string): Promise<T> {
  const response = await api.get(path);
  expect(response.ok(), `GET ${path}: ${response.status()} ${await response.text()}`).toBe(true);
  return (await response.json()) as T;
}

export interface Ref {
  id: string;
}
export interface PublicGroup extends Ref {
  code: string;
}
export interface PublicTeacher extends Ref {
  shortName: string;
}
export interface PublicRoom extends Ref {
  name: string;
  maxParallel: number;
}
export interface Lesson extends Ref {
  pair: number;
  startsAt: string | null;
  discipline: { shortName: string; name: string };
  teacher: { id: string; shortName: string; displayName: string } | null;
  room: { name: string } | null;
  group: { id: string; code: string };
  subgroup: number;
  kind: string;
}
export interface Day {
  date: string;
  parity: 'NUMERATOR' | 'DENOMINATOR' | 'BOTH';
  studyDay: boolean;
  lessons: Lesson[];
}
export interface Schedule {
  days: Day[];
}

export const PARITY_LABEL = { NUMERATOR: 'Чисельник', DENOMINATOR: 'Знаменник' } as const;

/** Пн..пт тижня, в який входить дата (ISO, без часових поясів). */
export function weekOf(date: string): { from: string; to: string } {
  const day = new Date(`${date}T12:00:00Z`);
  const shift = (day.getUTCDay() + 6) % 7;
  const monday = new Date(day.getTime() - shift * 86_400_000);
  const friday = new Date(monday.getTime() + 4 * 86_400_000);
  return { from: monday.toISOString().slice(0, 10), to: friday.toISOString().slice(0, 10) };
}

export function addDays(date: string, days: number): string {
  return new Date(new Date(`${date}T12:00:00Z`).getTime() + days * 86_400_000).toISOString().slice(0, 10);
}

/** Публічні довідники: id за кодом групи, прізвищем викладача, номером аудиторії. */
export async function publicCatalog(api: APIRequestContext) {
  const [groups, teachers, rooms] = await Promise.all([
    getJson<PublicGroup[]>(api, '/api/public/groups'),
    getJson<PublicTeacher[]>(api, '/api/public/teachers'),
    getJson<PublicRoom[]>(api, '/api/public/rooms'),
  ]);
  const find = <T extends Ref>(list: T[], match: (item: T) => boolean, what: string): T => {
    const item = list.find(match);
    if (!item) throw new Error(`На стенді немає ${what}: запустіть scripts/dev-stack.sh seed`);
    return item;
  };
  return {
    group: (code: string) => find(groups, (g) => g.code === code, `групи ${code}`),
    teacher: (name: string) => find(teachers, (t) => t.shortName === name, `викладача ${name}`),
    room: (name: string) => find(rooms, (r) => r.name === name, `аудиторії ${name}`),
  };
}

/** Горизонтальної прокрутки сторінки немає (вміст влазить у ширину вікна). */
export async function expectNoHorizontalScroll(page: Page): Promise<void> {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow, 'сторінка ширша за вікно').toBeLessThanOrEqual(0);
}
