/**
 * Інструменти обробників мок-API: шляхи, ProblemDetail як у бекенді, затримка, симуляція збоїв,
 * пагінація і читання тіла запиту.
 */
import { delay, http, HttpResponse } from 'msw';
import type { Conflict, FieldErrorDto, Page } from '@/api/types';

/** Той самий базовий шлях, що й API_BASE у src/api/client.ts (клієнт не імпортуємо: він тягне OIDC і window). */
export const API_PREFIX = (import.meta.env.VITE_API_BASE || '/api').replace(/\/$/, '');

/** Шлях для обробника: `apiPath('/public/meta')` → `/api/public/meta`. */
export function apiPath(path: string): string {
  return `${API_PREFIX}${path.startsWith('/') ? path : `/${path}`}`;
}

// ───────────────────────── ProblemDetail ─────────────────────────

const PROBLEMS: Readonly<Record<number, { slug: string; title: string }>> = {
  400: { slug: 'validation', title: 'Некоректні дані запиту' },
  401: { slug: 'unauthorized', title: 'Потрібно увійти в систему' },
  403: { slug: 'forbidden', title: 'Недостатньо прав' },
  404: { slug: 'not-found', title: 'Не знайдено' },
  409: { slug: 'conflict', title: 'Конфлікт з наявними даними' },
  410: { slug: 'gone', title: 'Посилання вже недійсне' },
  422: { slug: 'business-rule', title: 'Порушено правило розкладу' },
  429: { slug: 'rate-limited', title: 'Забагато запитів' },
};

export interface ProblemInit {
  status: number;
  /** Остання частина `type`: `urn:rozklad:problem:<slug>` (як ProblemTypes у core). */
  slug?: string;
  title?: string;
  detail?: string;
  errors?: FieldErrorDto[];
  conflicts?: Conflict[];
  /** Код бізнес-правила (лише 422). */
  code?: string;
}

/**
 * Відповідь `application/problem+json` у форматі бекенду (CHANGELOG-CONTRACTS): type, title, status,
 * detail, requestId, errors[] за полем, conflicts[]. Її можна і повернути, і кинути (`throw`) з обробника.
 */
export function problem(init: ProblemInit): Response {
  const known = PROBLEMS[init.status];
  const slug = init.slug ?? known?.slug ?? (init.status >= 500 ? 'internal' : 'error');
  const requestId = crypto.randomUUID();
  const body = {
    type: `urn:rozklad:problem:${slug}`,
    title: init.title ?? known?.title ?? (init.status >= 500 ? 'Внутрішня помилка сервера' : 'Помилка запиту'),
    status: init.status,
    detail: init.detail ?? null,
    requestId,
    ...(init.errors ? { errors: [...init.errors].sort((a, b) => a.field.localeCompare(b.field)) } : {}),
    ...(init.conflicts ? { conflicts: init.conflicts } : {}),
    ...(init.code ? { code: init.code } : {}),
  };
  return HttpResponse.json(body, {
    status: init.status,
    headers: { 'Content-Type': 'application/problem+json', 'X-Request-Id': requestId },
  });
}

export const badRequest = (detail: string, errors?: FieldErrorDto[]): Response => problem({ status: 400, detail, errors });

export const notFound = (detail: string): Response => problem({ status: 404, detail });

/** 409; з `conflicts` — це конфлікт розкладу (`schedule-conflict`). */
export const conflict = (detail: string, conflicts?: Conflict[]): Response =>
  problem({ status: 409, slug: conflicts?.length ? 'schedule-conflict' : 'conflict', detail, conflicts });

export const businessRule = (code: string, detail: string): Response =>
  problem({ status: 422, slug: 'business-rule', code, detail });

// ───────────────────────── Затримка і збої ─────────────────────────

/** Затримка відповіді: мс, `real` (MSW: 100–400 мс у браузері, ~5 мс у Node) або `infinite`. */
export type MockDelay = number | 'real' | 'infinite';

export function parseDelay(raw: string | null | undefined): MockDelay | null {
  if (!raw) return null;
  if (raw === 'real' || raw === 'infinite') return raw;
  const ms = Number(raw);
  return Number.isFinite(ms) && ms >= 0 ? ms : null;
}

const DEFAULT_DELAY: MockDelay = parseDelay(import.meta.env.VITE_MOCK_DELAY) ?? 'real';

/** Прапорець з адреси запиту або (у браузері) з адреси сторінки: `/g/4КІ1?mockError=503`. */
function controlFlag(request: Request, name: string): string | null {
  const fromRequest = new URL(request.url).searchParams.get(name);
  if (fromRequest !== null) return fromRequest;
  return typeof location === 'undefined' ? null : new URLSearchParams(location.search).get(name);
}

/** Чекає, як справжній сервер: `?mockDelay=2000|real|infinite` або VITE_MOCK_DELAY. */
export async function simulateLatency(request: Request): Promise<void> {
  const value = parseDelay(controlFlag(request, 'mockDelay')) ?? DEFAULT_DELAY;
  if (value === 0) return;
  await delay(value);
}

/**
 * Симульований збій за прапорцем `?mockError=<статус 400–599|network>`; `&mockErrorPath=<частина шляху>`
 * обмежує його одним ендпоінтом. null — збою немає. Неправильне значення прапорця — помилка, а не тиша.
 */
export function simulatedFailure(request: Request): Response | null {
  const raw = controlFlag(request, 'mockError');
  if (!raw) return null;
  const pathFilter = controlFlag(request, 'mockErrorPath');
  if (pathFilter && !new URL(request.url).pathname.includes(pathFilter)) return null;
  if (raw === 'network') return HttpResponse.error();
  const status = Number(raw);
  if (!Number.isInteger(status) || status < 400 || status > 599) {
    throw new Error(`mockError має бути статусом 400–599 або network, а не «${raw}»`);
  }
  return problem({ status, detail: `Симульована помилка мок-API (mockError=${status}).` });
}

/**
 * Спільна прослойка браузерного мок-API для всіх запитів до /api: спершу затримка й симуляція збою,
 * далі запит іде до обробника своєї області (резолвер нічого не повертає — MSW шукає наступний обробник).
 */
export const controlHandler = http.all(apiPath('/*'), async ({ request }) => {
  await simulateLatency(request);
  return simulatedFailure(request) ?? undefined;
});

// ───────────────────────── Запити й сторінки ─────────────────────────

/** Тіло JSON або 400 ProblemDetail (кидається як відповідь MSW). */
export async function readJson<T>(request: Request): Promise<T> {
  try {
    return (await request.json()) as T;
  } catch {
    throw badRequest('Тіло запиту має бути коректним JSON.');
  }
}

/** Обовʼязковий параметр запиту або 400 з помилкою поля. */
export function requiredParam(url: URL, name: string): string {
  const value = url.searchParams.get(name);
  if (value === null || value.trim() === '') {
    throw badRequest(`Не вказано параметр ${name}.`, [{ field: name, message: 'Обовʼязковий параметр' }]);
  }
  return value;
}

/**
 * Page<T> за параметрами Spring Data: `page` з 0, `size` (типово 20, не більше `maxSize` — у кожного
 * сервісу своя межа: core каталог 200, importer 100, notification 200).
 */
export function paginate<T>(items: readonly T[], url: URL, defaultSize = 20, maxSize = 100): Page<T> {
  const page = Number(url.searchParams.get('page') ?? 0);
  const size = Number(url.searchParams.get('size') ?? defaultSize);
  if (!Number.isInteger(page) || page < 0 || !Number.isInteger(size) || size < 1 || size > maxSize) {
    throw badRequest(`Некоректні параметри сторінки: page ≥ 0, size від 1 до ${maxSize}.`);
  }
  return { items: items.slice(page * size, (page + 1) * size), page, size, total: items.length };
}
