import { expect, test, type APIRequestContext, type Locator, type Page } from '@playwright/test';
import {
  addDays,
  expectNoHorizontalScroll,
  getJson,
  PARITY_LABEL,
  publicCatalog,
  weekOf,
  type Day,
  type Lesson,
  type Schedule,
} from './support/real';

/**
 * Публічний розклад на справжніх даних (Excel-розклад коледжу, розпорядження №35 від 19.10.2026).
 * Що має бути на сторінці, тест бере з публічного API того самого стенду — так перевіряється і сторінка,
 * і те, що вона показує саме відповідь сервера, а не мок. Ширини: 1440, 768, 375 (проєкти конфігурації).
 */

/** 19.10.2026 — 8-й тиждень, знаменник (DOMAIN, сід стенду); того дня діє розпорядження №35. */
const ORDER_DAY = '2026-10-19';

let api: APIRequestContext;
test.beforeAll(async ({ playwright }) => {
  api = await playwright.request.newContext({ baseURL: process.env.REAL_GATEWAY_URL || 'http://localhost:8080' });
});
test.afterAll(async () => {
  await api.dispose();
});

function escape(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** Кнопка пари: доступна назва починається з «N пара, 08:30–09:30. Назва дисципліни». */
function lessonButtons(scope: Page | Locator, lesson: Lesson): Locator {
  const time = lesson.startsAt ? `, ${escape(lesson.startsAt)}` : '';
  const name = escape(lesson.discipline.name || lesson.discipline.shortName);
  return scope.getByRole('button', { name: new RegExp(`^${lesson.pair} пара${time}[^.]*\\. ${name}`) });
}

/** Скільки разів кожна пара (номер + дисципліна) зустрічається в днях — стільки ж кнопок має бути на екрані. */
function expectedCounts(days: Day[]): Map<string, { lesson: Lesson; count: number }> {
  const counts = new Map<string, { lesson: Lesson; count: number }>();
  for (const lesson of days.flatMap((day) => day.lessons)) {
    const key = `${lesson.pair}|${lesson.startsAt}|${lesson.discipline.name || lesson.discipline.shortName}`;
    const entry = counts.get(key);
    if (entry) entry.count += 1;
    else counts.set(key, { lesson, count: 1 });
  }
  return counts;
}

async function expectLessons(scope: Page | Locator, days: Day[]): Promise<void> {
  const counts = expectedCounts(days);
  expect(counts.size, 'у розкладі на стенді мають бути пари').toBeGreaterThan(0);
  for (const { lesson, count } of counts.values()) {
    await expect(lessonButtons(scope, lesson), `${lesson.pair} пара ${lesson.discipline.shortName}`).toHaveCount(count);
  }
}

async function openSchedule(page: Page, path: string): Promise<void> {
  await page.goto(path);
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible({ timeout: 30_000 });
}

test('пошук «4КІ1» на головній веде до розкладу групи з парами з Excel', async ({ page }) => {
  const catalog = await publicCatalog(api);
  const group = catalog.group('4КІ1');
  await page.goto('/');
  const search = page.getByRole('combobox', { name: /Знайти розклад/ });
  await search.fill('4КІ1');
  await page.getByRole('option', { name: /^4КІ1/ }).first().click();
  await expect(page).toHaveURL(/\/g\/4%D0%9A%D0%861/);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(/Розклад групи\s*4КІ1/);

  // тиждень за замовчуванням — найближчий навчальний; парність кожного дня — як у сервера
  const shown = new URL(page.url()).searchParams.get('date');
  const meta = await getJson<{ today: string }>(api, '/api/public/meta');
  const weekday = new Date(`${meta.today}T12:00:00Z`).getUTCDay();
  const nearestStudyDay = addDays(meta.today, weekday === 6 ? 2 : weekday === 0 ? 1 : 0);
  const week = weekOf(shown ?? nearestStudyDay);
  const schedule = await getJson<Schedule>(api, `/api/public/schedule/group/${group.id}?from=${week.from}&to=${week.to}`);
  const parity = schedule.days[0]?.parity;
  if (parity === 'NUMERATOR' || parity === 'DENOMINATOR') {
    await expect(page.getByRole('main').getByText(PARITY_LABEL[parity], { exact: true }).first()).toBeVisible();
  }
  await expectNoHorizontalScroll(page);
});

test('тиждень 19.10.2026 сіткою: знаменник, 8-й тиждень, усі пари й заміни розпорядження №35', async ({ page, isMobile }) => {
  test.skip(isMobile, 'На телефоні тиждень показується списком днів — його перевіряє наступний тест');
  const catalog = await publicCatalog(api);
  const group = catalog.group('4КІ1');
  const week = weekOf(ORDER_DAY);
  const schedule = await getJson<Schedule>(api, `/api/public/schedule/group/${group.id}?from=${week.from}&to=${week.to}`);
  expect(schedule.days.map((d) => d.parity)).toEqual(Array(5).fill('DENOMINATOR'));

  await openSchedule(page, `/g/4%D0%9A%D0%861?view=week&date=${ORDER_DAY}`);
  await expect(page.getByText('8-й тиждень').first()).toBeVisible();
  const table = page.getByRole('table', { name: /Розклад 4КІ1 на 19–23 жовтня, тиждень — знаменник/ });
  await expect(table).toBeVisible();
  await expectLessons(table, schedule.days);

  // заміни розпорядження №35 у сітці позначені як заміни (взаємна заміна СТП ↔ НДЕКС)
  const substituted = schedule.days.flatMap((d) => d.lessons).filter((l) => l.kind === 'SUBSTITUTED');
  expect(substituted.length, 'у 4КІ1 19.10 є заміни з розпорядження №35').toBeGreaterThan(0);
  for (const lesson of substituted) {
    await expect(lessonButtons(table, lesson).filter({ hasText: /./ }).first()).toHaveAccessibleName(/Заміна/);
  }
  await expect(page.getByRole('region', { name: 'Зміни цього тижня' })).toContainText('Кренцін');
  await expectNoHorizontalScroll(page);
});

test('день 19.10.2026 списком: ті самі пари, що віддає сервер', async ({ page }) => {
  const catalog = await publicCatalog(api);
  const group = catalog.group('4КІ1');
  const schedule = await getJson<Schedule>(api, `/api/public/schedule/group/${group.id}?from=${ORDER_DAY}&to=${ORDER_DAY}`);

  await openSchedule(page, `/g/4%D0%9A%D0%861?view=day&date=${ORDER_DAY}`);
  await expect(page.getByRole('radiogroup', { name: 'День тижня' })).toBeVisible();
  await expect(page.getByRole('main').getByText('Знаменник', { exact: true }).first()).toBeVisible();
  await expectLessons(page.getByRole('main'), schedule.days);
  await expectNoHorizontalScroll(page);
});

test('розклад викладача Кренціна і аудиторії 301', async ({ page }) => {
  const catalog = await publicCatalog(api);
  const teacher = catalog.teacher('Кренцін');
  const room = catalog.room('301');
  const day = '2026-11-16';

  const mine = await getJson<Schedule>(api, `/api/public/schedule/teacher/${teacher.id}?from=${day}&to=${day}`);
  await openSchedule(page, `/t/${teacher.id}?view=day&date=${day}`);
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Кренцін');
  await expectLessons(page.getByRole('main'), mine.days);
  await expectNoHorizontalScroll(page);

  const busy = await getJson<Schedule>(api, `/api/public/schedule/room/${room.id}?from=${day}&to=${day}`);
  await openSchedule(page, `/r/${room.id}?view=day&date=${day}`);
  await expect(page.getByRole('heading', { level: 1 })).toContainText('301');
  await expectLessons(page.getByRole('main'), busy.days);
  await expectNoHorizontalScroll(page);
});

test('спортзал: скільки груп приймає одночасно', async ({ page }) => {
  const catalog = await publicCatalog(api);
  const gym = catalog.room('с.з.');
  expect(gym.maxParallel).toBeGreaterThan(1);
  await openSchedule(page, `/r/${gym.id}`);
  await expect(page.getByRole('main')).toContainText(`до ${gym.maxParallel} груп одночасно`);
});

test('дзвінки: профіль і час пар з сервера', async ({ page }) => {
  const monday = await getJson<{ profile: { name: string }; slots: Array<{ pair: number; startsAt: string; endsAt: string }> }>(
    api,
    '/api/public/bells?date=2026-11-16',
  );
  await openSchedule(page, '/bells');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Дзвінки');
  await expect(page.getByRole('main')).toContainText(monday.profile.name);
  for (const slot of monday.slots) {
    await expect(page.getByRole('main').getByText(`${slot.startsAt}–${slot.endsAt}`).first()).toBeVisible();
  }
  await expectNoHorizontalScroll(page);
});

test('заміни на 19.10.2026: розпорядження №35, групи й файли документа', async ({ page }) => {
  const changes = await getJson<{ order: { id: string; number: number } | null; groups: Array<{ group: { code: string }; items: unknown[] }> }>(
    api,
    `/api/public/changes?date=${ORDER_DAY}`,
  );
  expect(changes.order?.number).toBe(35);
  const total = changes.groups.reduce((sum, g) => sum + g.items.length, 0);

  await openSchedule(page, `/changes?date=${ORDER_DAY}`);
  await expect(page.getByRole('heading', { level: 1 })).toContainText('19 жовтня');
  await expect(page.getByText('Розпорядження №35', { exact: false }).first()).toBeVisible();
  await expect(page.getByRole('main')).toContainText(`${total} змін у ${changes.groups.length} груп`);
  for (const { group } of changes.groups) {
    await expect(page.getByRole('link', { name: new RegExp(`^${group.code}`) }).first()).toBeVisible();
  }

  // PDF і PNG розпорядження відкриваються без входу
  for (const format of ['PDF', 'PNG']) {
    const link = page.getByRole('link', { name: format, exact: true });
    const href = await link.getAttribute('href');
    expect(href).toContain(`/api/documents/orders/${changes.order?.id}.${format.toLowerCase()}`);
    const file = await page.request.get(href ?? '');
    expect(file.status()).toBe(200);
    expect(file.headers()['content-type']).toContain(format === 'PDF' ? 'application/pdf' : 'image/png');
  }
  await expectNoHorizontalScroll(page);
});
