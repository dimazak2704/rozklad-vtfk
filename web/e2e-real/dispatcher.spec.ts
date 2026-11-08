import { execFileSync } from 'node:child_process';
import { expect, test, type APIRequestContext } from '@playwright/test';
import {
  addDays,
  apiAs,
  getJson,
  login,
  pageAs,
  publicCatalog,
  type Lesson,
  type Schedule,
} from './support/real';

/**
 * Робота диспетчера і викладача на справжньому стенді (лише 1440 px: панель розрахована на великий екран).
 * Сценарій заміни створює дані: відсутність Кренціна на одну пару, заміну Непийводою, розпорядження.
 * Дату бере першу вільну попереду (без відсутностей Кренціна й без розпорядження), тож прогін можна
 * повторювати; чисті демо-дані — `scripts/dev-stack.sh reset-data --yes`, `up --no-build`, `seed`.
 */
test.describe.configure({ mode: 'serial' });

const ORDER_DAY = '2026-10-19';

interface OrderPage {
  items: Array<{ id: string; number: number | null; date: string; status: string }>;
  total: number;
}
interface Option {
  kind: string;
  title: string;
  teacher: { id: string; shortName: string } | null;
}

let admin: APIRequestContext;
test.beforeAll(async () => {
  admin = await apiAs('ADMIN');
});
test.afterAll(async () => {
  await admin.dispose();
});

/**
 * Навчальний день попереду, коли в Кренціна є пара, а Непийвода може її замінити, і цього дня ще немає
 * ні відсутностей Кренціна, ні розпорядження (сценарій не залежить від попередніх прогонів).
 */
async function freeLesson(): Promise<{ date: string; lesson: Lesson; option: Option }> {
  const catalog = await publicCatalog(admin);
  const krentsin = catalog.teacher('Кренцін');
  const meta = await getJson<{ today: string; semester: { endsOn: string } }>(admin, '/api/public/meta');
  for (let date = addDays(meta.today, 7); date <= meta.semester.endsOn; date = addDays(date, 1)) {
    const schedule = await getJson<Schedule>(admin, `/api/public/schedule/teacher/${krentsin.id}?from=${date}&to=${date}`);
    const lessons = schedule.days.flatMap((day) => day.lessons).filter((l) => l.kind === 'REGULAR');
    if (lessons.length === 0) continue;
    const absences = await getJson<unknown[]>(admin, `/api/admin/absences?from=${date}&to=${date}&teacherId=${krentsin.id}`);
    const orders = await getJson<OrderPage>(admin, `/api/admin/orders?from=${date}&to=${date}`);
    if (absences.length > 0 || orders.total > 0) continue;
    for (const lesson of lessons) {
      const { options } = await getJson<{ options: Option[] }>(admin, `/api/admin/lessons/${lesson.id}/options`);
      const option = options.find((o) => o.kind === 'REPLACE_DISCIPLINE' && o.teacher?.shortName === 'Непийвода');
      if (option) return { date, lesson, option };
    }
  }
  throw new Error('До кінця семестру немає вільного дня для сценарію заміни: перезаповніть стенд (reset-data, seed)');
}

test('відсутність → заміна Непийводою → вона підтверджує в кабінеті → розпорядження → заміна на сайті', async ({ page, browser, baseURL }) => {
  const { date, lesson, option } = await freeLesson();
  const group = lesson.group.code;

  // 1. Диспетчер: нова відсутність Кренціна лише на цю пару
  await login(page, 'ADMIN', `/admin/changes?date=${date}`);
  await expect(page.getByRole('heading', { level: 1, name: 'Заміни' })).toBeVisible({ timeout: 30_000 });
  await page.getByRole('button', { name: /Нова відсутність/ }).first().click();
  const form = page.getByRole('dialog', { name: 'Нова відсутність' });
  await form.getByRole('combobox', { name: 'Викладач' }).click();
  await page.getByPlaceholder('Прізвище, напр. «кренц»').fill('кренц');
  await page.getByRole('option', { name: 'Кренцін' }).click();
  await form.getByRole('radio', { name: 'Окремі пари' }).click();
  await form.getByRole('button', { name: new RegExp(`^${lesson.pair} пара`) }).click();
  await expect(form).toContainText(group);
  await form.getByRole('button', { name: 'Додати відсутність' }).click();
  await expect(form).toBeHidden();
  await expect(page.getByRole('heading', { level: 3, name: 'Кренцін' })).toBeVisible();

  // 2. Пара відкрита, серед варіантів «інша дисципліна» — пара Непийводи
  const decision = page.getByRole('region', { name: 'Рішення щодо пари' });
  await page.getByRole('tab', { name: /Інша дисципліна/ }).click();
  await page.getByRole('button', { name: `Обрати: ${option.title}` }).click();
  await expect(decision.getByText('Чекає відповіді')).toBeVisible();

  // 3. Непийвода у своєму кабінеті бачить запит і підтверджує
  const teacher = await pageAs(browser, 'TEACHER2', baseURL ?? '', '/me');
  try {
    await expect(teacher.getByRole('heading', { level: 1 })).toHaveText('Мій розклад', { timeout: 30_000 });
    const request = teacher.getByRole('article', { name: new RegExp(`^Запит на заміну: ${group}, .*${lesson.pair} пара$`) });
    await expect(request).toBeVisible();
    await request.getByRole('button', { name: 'Підтверджую' }).click();
    await expect(teacher.getByText('Заміну підтверджено')).toBeVisible();
    await expect(request).toHaveCount(0);
  } finally {
    await teacher.context().close();
  }

  // 4. У диспетчера стан оновився сам (SSE): підтверджено, рядок у чернетці розпорядження
  await expect(decision.getByText('Підтверджено', { exact: true }).first()).toBeVisible({ timeout: 20_000 });
  await decision.getByRole('link', { name: 'Переглянути чернетку' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Розпорядження' })).toBeVisible();
  const draft = page.getByRole('article', { name: /\(чернетка\)$/ });
  await expect(draft.getByRole('row').filter({ hasText: group })).toContainText('Непийвода');

  // 5. Публікація з номером, який присвоїть сервер
  const publish = page.getByRole('button', { name: /^Опублікувати №\d+$/ });
  const label = (await publish.textContent()) ?? '';
  const number = /№(\d+)/.exec(label)?.[1];
  expect(number).toBeTruthy();
  await publish.click();
  const confirm = page.getByRole('dialog', { name: 'Опублікувати розпорядження?' });
  await confirm.getByRole('button', { name: `Опублікувати №${number}` }).click();
  await expect(page.getByText(`Розпорядження №${number} опубліковано`)).toBeVisible();

  // 6. Публічно: заміна в розпорядженні дня і в розкладі групи
  const visitor = await browser.newContext({ baseURL, locale: 'uk-UA', timezoneId: 'Europe/Kyiv' });
  try {
    const pub = await visitor.newPage();
    await pub.goto(`/changes?date=${date}`);
    await expect(pub.getByText(`Розпорядження №${number}`, { exact: false }).first()).toBeVisible({ timeout: 30_000 });
    await expect(pub.getByRole('main')).toContainText('Непийвода');
    await expect(pub.getByRole('link', { name: new RegExp(`^${group}`) }).first()).toBeVisible();

    await pub.goto(`/g/${encodeURIComponent(group)}?view=day&date=${date}`);
    const cell = pub.getByRole('main').getByRole('button', { name: new RegExp(`^${lesson.pair} пара`) }).filter({ hasText: 'Непийвода' });
    await expect(cell.first()).toHaveAccessibleName(/Заміна, замість: СТП, Кренцін/);
  } finally {
    await visitor.close();
  }
});

test('відсутність на два дні: пари обох днів окремо, з датою від сервера', async ({ page }) => {
  const catalog = await publicCatalog(admin);
  const meta = await getJson<{ today: string; semester: { endsOn: string } }>(admin, '/api/public/meta');
  // понеділок через два тижні й далі, коли Стець має пари і в понеділок, і у вівторок, а відсутностей ще немає
  const teacher = catalog.teacher('Стець');
  let monday = addDays(meta.today, 14);
  while (new Date(`${monday}T12:00:00Z`).getUTCDay() !== 1) monday = addDays(monday, 1);
  let found: { monday: string; tuesday: string } | null = null;
  for (; monday < meta.semester.endsOn && !found; monday = addDays(monday, 7)) {
    const tuesday = addDays(monday, 1);
    const week = await getJson<Schedule>(admin, `/api/public/schedule/teacher/${teacher.id}?from=${monday}&to=${tuesday}`);
    const busy = await getJson<unknown[]>(admin, `/api/admin/absences?from=${monday}&to=${tuesday}&teacherId=${teacher.id}`);
    if (week.days.every((day) => day.lessons.length > 0) && busy.length === 0) found = { monday, tuesday };
  }
  expect(found, 'вільні понеділок і вівторок Стеця до кінця семестру').toBeTruthy();
  const { monday: from, tuesday: to } = found ?? { monday: '', tuesday: '' };

  await login(page, 'ADMIN', `/admin/changes?date=${from}`);
  await expect(page.getByRole('heading', { level: 1, name: 'Заміни' })).toBeVisible({ timeout: 30_000 });
  await page.getByRole('button', { name: /Нова відсутність/ }).first().click();
  const form = page.getByRole('dialog', { name: 'Нова відсутність' });
  await form.getByRole('combobox', { name: 'Викладач' }).click();
  await page.getByPlaceholder('Прізвище, напр. «кренц»').fill('стець');
  await page.getByRole('option', { name: 'Стець' }).click();
  await form.getByRole('textbox', { name: /^По/ }).fill(to);
  const created = page.waitForResponse((r) => r.url().endsWith('/api/admin/absences') && r.request().method() === 'POST');
  await form.getByRole('button', { name: 'Додати відсутність' }).click();
  const absence = (await (await created).json()) as { id: string };
  try {
    const affected = await getJson<Array<{ date: string }>>(admin, `/api/admin/absences/${absence.id}/affected`);
    expect(new Set(affected.map((item) => item.date))).toEqual(new Set([from, to]));

    await page.getByRole('radio', { name: 'Тиждень' }).click();
    const section = page.getByRole('region', { name: 'Стець' });
    await expect(section).toContainText(`${affected.length} з ${affected.length} без рішення`);
    await expect(section.getByRole('heading', { level: 4 })).toHaveCount(2);
    await expect(page.getByText(/Сервер не надіслав дати/)).toHaveCount(0);
  } finally {
    // відсутність тестова: скасовуємо, щоб не лишати зачеплених пар на стенді
    const cancel = await admin.delete(`/api/admin/absences/${absence.id}`);
    expect(cancel.ok(), `скасування відсутності: ${cancel.status()}`).toBe(true);
  }
});

test('кабінет Кренціна: свої пари з сервера і стан Telegram', async ({ page }) => {
  const day = '2026-11-16';
  const catalog = await publicCatalog(admin);
  const own = await getJson<Schedule>(admin, `/api/public/schedule/teacher/${catalog.teacher('Кренцін').id}?from=${day}&to=${day}`);
  const lessons = own.days.flatMap((d) => d.lessons);
  expect(lessons.length).toBeGreaterThan(0);

  await login(page, 'TEACHER', `/me?view=day&date=${day}`);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Мій розклад', { timeout: 30_000 });
  for (const lesson of lessons) {
    await expect(page.getByRole('main').getByRole('button', { name: new RegExp(`^${lesson.pair} пара`) }).first()).toBeVisible();
  }
  // бот на стенді вимкнено і Кренцін його не підключав: кабінет пропонує підключення
  const telegram = page.getByRole('region', { name: 'Telegram' });
  await expect(telegram.getByRole('button', { name: 'Підключити Telegram' })).toBeVisible();
});

test('панель диспетчера на дату з розпорядженням №35', async ({ page }) => {
  const dashboard = await getJson<{ confirmed: number; absences: number }>(admin, `/api/admin/dashboard?date=${ORDER_DAY}`);
  await login(page, 'ADMIN', `/admin?date=${ORDER_DAY}`);
  await expect(page.getByRole('heading', { level: 1, name: 'Панель диспетчера' })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole('main')).toContainText('19 жовтня');
  await expect(page.getByRole('main')).toContainText('№35');
  expect(dashboard.absences).toBeGreaterThan(0);
});

test('розпорядження №35 від 19.10.2026: аркуш з даними сервера і PDF для друку', async ({ page }) => {
  const orders = await getJson<OrderPage>(admin, `/api/admin/orders?from=${ORDER_DAY}&to=${ORDER_DAY}&status=PUBLISHED`);
  const published = orders.items.find((o) => o.number === 35);
  expect(published, 'на стенді є опубліковане розпорядження №35 (seed)').toBeTruthy();
  const order = await getJson<{ items: Array<{ groupCode: string; toTeacher: string | null }>; notes: string[]; signatoryName: string }>(
    admin,
    `/api/admin/orders/${published?.id}`,
  );

  await login(page, 'ADMIN', `/admin/orders?date=${ORDER_DAY}`);
  const sheet = page.getByRole('article', { name: /^Розпорядження №35 на 19\sжовтня 2026/ });
  await expect(sheet).toBeVisible({ timeout: 30_000 });
  await expect(sheet).toContainText('По коледжу про деякі зміни в розкладі занять');
  for (const item of order.items) await expect(sheet).toContainText(item.groupCode);
  for (const note of order.notes) await expect(sheet).toContainText(note.slice(0, 40));
  await expect(sheet).toContainText(order.signatoryName);

  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Завантажити PDF' }).click();
  const file = await download;
  expect(file.suggestedFilename()).toBe('Розпорядження №35 від 19.10.2026.pdf');
  const stream = await file.createReadStream();
  const head = await new Promise<string>((resolve, reject) => {
    stream.once('data', (chunk) => resolve(chunk.toString('latin1', 0, 5)));
    stream.once('error', reject);
  });
  expect(head).toBe('%PDF-');
});

test('імпорт: завдання з сервера посторінково, відкривається звіт', async ({ page }) => {
  const jobs = await getJson<{ items: Array<{ fileName: string }>; total: number; size: number }>(admin, '/api/import/jobs?page=0&size=20');
  expect(jobs.total, 'на стенді є імпорт Excel-розкладу (seed)').toBeGreaterThan(0);

  await login(page, 'ADMIN', '/admin/import');
  const table = page.getByRole('table', { name: 'Завдання імпорту' });
  await expect(table.getByRole('row').filter({ hasText: jobs.items[0]?.fileName ?? '' }).first()).toBeVisible({ timeout: 30_000 });
  await expect(table.getByRole('row')).toHaveCount(Math.min(jobs.total, jobs.size) + 1);
  if (jobs.total > jobs.size) await expect(page.getByRole('navigation', { name: /Сторінки/ })).toBeVisible();

  await table.getByRole('row').filter({ hasText: 'schedule-2025-26.xlsx' }).first().click();
  await expect(page.getByRole('main')).toContainText('Застосовано');
});

test('планування: опублікована версія розкладу, генератор і навантаження відкриваються з даними', async ({ page }) => {
  const meta = await getJson<{ semester: { id: string } }>(admin, '/api/public/meta');
  const versions = await getJson<Array<{ name: string; status: string; lessonCount: number }>>(
    admin,
    `/api/admin/timetable/versions?semesterId=${meta.semester.id}`,
  );
  const published = versions.find((v) => v.status === 'PUBLISHED');
  expect(published, 'на стенді є опублікована версія (seed)').toBeTruthy();

  await login(page, 'ADMIN', '/admin/timetable');
  await expect(page.getByRole('heading', { level: 1, name: 'Базовий розклад' })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole('main')).toContainText(published?.name ?? '');
  await expect(page.getByRole('main')).toContainText(`${published?.lessonCount} пар`);
  await expect(page.getByRole('main')).toContainText('Опублікована');

  await page.goto('/admin/generation');
  await expect(page.getByRole('heading', { level: 1, name: 'Генератор розкладу' })).toBeVisible();
  await expect(page.getByRole('main')).toContainText(published?.name ?? '');

  await page.goto('/admin/load');
  await expect(page.getByRole('heading', { level: 1, name: 'Навантаження' })).toBeVisible();
  await expect(page.getByRole('table').first().getByRole('row').nth(1)).toBeVisible();
});

test('налаштування: помилки перевірки від сервера показані біля полів українською', async ({ page }) => {
  const before = await getJson<Record<string, unknown>>(admin, '/api/admin/settings');
  await login(page, 'ADMIN', '/admin/settings');
  await expect(page.getByRole('heading', { level: 1, name: 'Налаштування' })).toBeVisible({ timeout: 30_000 });

  // форма не пропустить неправильні значення сама, тож підміняємо тіло запиту — відповідає справжній core
  await page.route('**/api/admin/settings', async (route) => {
    if (route.request().method() !== 'PUT') return route.continue();
    const body = { ...(route.request().postDataJSON() as Record<string, unknown>), confirmTimeoutMinutes: 2, signatoryPosition: ' ' };
    await route.continue({ postData: JSON.stringify(body) });
  });
  const position = page.getByRole('textbox', { name: 'Посада підписанта' });
  await position.fill(`${await position.inputValue()} `);
  await page.getByRole('button', { name: 'Зберегти' }).click();

  await expect(page.getByText('Від 5 до 1440 хвилин')).toBeVisible();
  await expect(page.getByText('Вкажіть посаду підписанта')).toBeVisible();
  await expect(page.getByText('Не вдалося зберегти налаштування')).toBeVisible();
  // нічого не збереглося
  expect(await getJson<Record<string, unknown>>(admin, '/api/admin/settings')).toEqual(before);
});

/**
 * Сервіс упав: importer зупинено на час тесту (docker stop), gateway відповідає 503, сторінка імпорту показує
 * помилку з повтором, а решта панелі працює. Лише з REAL_STOP_SERVICES=1: тест зупиняє контейнер стенду.
 */
test('importer зупинено: імпорт показує помилку сервера з повтором, панель працює', async ({ page }) => {
  test.skip(process.env.REAL_STOP_SERVICES !== '1', 'зупиняє контейнер importer: лише з REAL_STOP_SERVICES=1');
  test.setTimeout(240_000);
  const container = process.env.REAL_IMPORTER_CONTAINER || 'rozklad-importer-1';
  await login(page, 'ADMIN', '/admin');
  await expect(page.getByRole('heading', { level: 1, name: 'Панель диспетчера' })).toBeVisible({ timeout: 30_000 });

  execFileSync('docker', ['stop', container], { stdio: 'ignore' });
  try {
    const failed = page.waitForResponse((r) => r.url().includes('/api/import/jobs') && r.status() === 503);
    await page.goto('/admin/import');
    expect((await failed).status()).toBe(503);
    // після повторів запиту (5xx повторюються двічі) — помилка в таблиці завдань з кнопкою повтору
    const table = page.getByRole('table', { name: 'Завдання імпорту' });
    await expect(table.getByRole('alert')).toContainText('Сервіс тимчасово недоступний', { timeout: 30_000 });
    await expect(table.getByRole('alert')).toContainText('Спробуйте ще раз за хвилину.');
    await expect(table.getByRole('button', { name: /Спробувати ще раз/ })).toBeVisible();
    await expect(page.getByRole('heading', { level: 1, name: 'Імпорт' })).toBeVisible();
  } finally {
    execFileSync('docker', ['start', container], { stdio: 'ignore' });
  }
  // сервіс повернувся — «Спробувати ще раз» завантажує список
  await expect
    .poll(() => execFileSync('docker', ['inspect', '-f', '{{.State.Health.Status}}', container]).toString().trim(), { timeout: 180_000 })
    .toBe('healthy');
  await page.getByRole('table', { name: 'Завдання імпорту' }).getByRole('button', { name: /Спробувати ще раз/ }).click();
  await expect(page.getByRole('table', { name: 'Завдання імпорту' }).getByRole('row').filter({ hasText: 'schedule-2025-26.xlsx' }).first()).toBeVisible();
});
