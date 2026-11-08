import type { Conflict, FieldErrorDto, ProblemDetail } from './types';

export type ApiErrorKind = 'http' | 'network' | 'timeout' | 'aborted' | 'parse';

/** Зрозумілі повідомлення за статусом, коли сервер не дав власного `detail`. */
const STATUS_MESSAGES: Record<number, string> = {
  400: 'Перевірте введені дані.',
  401: 'Потрібно увійти в систему.',
  403: 'Недостатньо прав для цієї дії.',
  404: 'Нічого не знайдено.',
  409: 'Дію не виконано: є конфлікт з іншими даними.',
  410: 'Посилання вже недійсне.',
  413: 'Файл завеликий.',
  415: 'Цей тип файлу не підтримується.',
  422: 'Дію не можна виконати за правилами розкладу.',
  429: 'Забагато запитів. Спробуйте за хвилину.',
};

/**
 * Українські повідомлення для кодів бізнес-правил (422 `code`) та інших відомих відмов бекенду.
 * Сервер і сам надсилає `detail` українською; тут — коротші тексти для людини, яка діє в інтерфейсі,
 * щоб те саме правило звучало однаково в кабінеті викладача й у панелі диспетчера.
 */
export const PROBLEM_CODE_MESSAGES: Readonly<Record<string, string>> = {
  ABSENCE_STARTED: 'Скасувати можна лише відсутність, яка ще не почалася. Якщо плани змінилися, повідомте диспетчера.',
  ABSENCE_IN_PAST: 'Не можна повідомити про відсутність, яка вже минула.',
  ABSENCE_TEACHER_FIXED: 'Викладача відсутності змінити не можна: скасуйте її та створіть нову.',
  ABSENCE_CANCELLED: 'Цю відсутність уже скасовано — оновіть список.',
  ABSENCE_MISMATCH: 'Ця відсутність належить іншому викладачу.',
  'teacher-not-linked': 'Ваш обліковий запис ще не повʼязаний з викладачем. Зверніться до диспетчера.',
};

/** Повідомлення 409 для оптимістичного блокування (`stale-data`): хтось інший змінив запис раніше. */
export const STALE_DATA_MESSAGE = 'Дані вже змінив хтось інший. Ми оновили їх — перевірте й повторіть дію.';

export function defaultMessage(status: number, kind: ApiErrorKind): string {
  if (kind === 'network') return 'Немає звʼязку із сервером. Перевірте зʼєднання і спробуйте ще раз.';
  if (kind === 'timeout') return 'Сервер довго не відповідає. Спробуйте ще раз.';
  if (kind === 'aborted') return 'Запит скасовано.';
  if (kind === 'parse') return 'Сервер повернув незрозумілу відповідь.';
  if (status >= 500) return 'Сервер тимчасово недоступний. Спробуйте ще раз за хвилину.';
  return STATUS_MESSAGES[status] ?? 'Щось пішло не так.';
}

export interface ApiErrorInit {
  status: number;
  kind?: ApiErrorKind;
  title?: string;
  detail?: string;
  type?: string;
  errors?: FieldErrorDto[];
  conflicts?: Conflict[];
  /** Код бізнес-правила з 422 (`code`). */
  code?: string;
  requestId?: string;
  cause?: unknown;
}

/** Помилка API: розібраний ProblemDetail з українським повідомленням для показу. */
export class ApiError extends Error {
  readonly status: number;
  readonly kind: ApiErrorKind;
  readonly title: string;
  readonly detail: string | undefined;
  readonly type: string | undefined;
  readonly errors: FieldErrorDto[];
  readonly conflicts: Conflict[];
  readonly code: string | undefined;
  readonly requestId: string | undefined;

  constructor(init: ApiErrorInit) {
    const kind = init.kind ?? 'http';
    const known = init.code ? PROBLEM_CODE_MESSAGES[init.code] : undefined;
    const message = known ?? (init.detail || defaultMessage(init.status, kind));
    super(message, { cause: init.cause });
    this.name = 'ApiError';
    this.status = init.status;
    this.kind = kind;
    this.title = init.title || defaultMessage(init.status, kind);
    this.detail = init.detail;
    this.type = init.type;
    this.errors = init.errors ?? [];
    this.conflicts = init.conflicts ?? [];
    this.code = init.code;
    this.requestId = init.requestId;
  }

  get isValidation(): boolean {
    return this.status === 400 && this.errors.length > 0;
  }

  get isUnauthorized(): boolean {
    return this.status === 401;
  }

  get isForbidden(): boolean {
    return this.status === 403;
  }

  get isNotFound(): boolean {
    return this.status === 404;
  }

  get isConflict(): boolean {
    return this.status === 409;
  }

  /** 409 оптимістичного блокування: запис змінили між читанням і збереженням (`urn:rozklad:problem:stale-data`). */
  get isStaleData(): boolean {
    return this.status === 409 && (this.type?.endsWith(':stale-data') ?? false);
  }

  /** Повторювати має сенс лише мережеві збої та 5xx/429. */
  get isRetryable(): boolean {
    return this.kind === 'network' || this.kind === 'timeout' || this.status >= 500 || this.status === 429;
  }

  /** `{ field: message }` для react-hook-form `setError`. */
  fieldErrors(): Record<string, string> {
    const out: Record<string, string> = {};
    for (const error of this.errors) {
      if (!(error.field in out)) out[error.field] = error.message;
    }
    return out;
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}

function isProblemDetail(value: unknown): value is ProblemDetail {
  return typeof value === 'object' && value !== null;
}

/** Будує ApiError з HTTP-відповіді (problem+json, json або порожнє тіло). */
export async function errorFromResponse(response: Response): Promise<ApiError> {
  const headerRequestId = response.headers.get('X-Request-Id') ?? undefined;
  const contentType = response.headers.get('Content-Type') ?? '';
  let problem: ProblemDetail = {};
  if (contentType.includes('json')) {
    try {
      const body: unknown = await response.json();
      if (isProblemDetail(body)) problem = body;
    } catch {
      // тіло не JSON — лишаємо повідомлення за статусом
    }
  }
  return new ApiError({
    status: response.status,
    title: problem.title,
    detail: problem.detail,
    type: problem.type,
    errors: Array.isArray(problem.errors) ? problem.errors : [],
    conflicts: Array.isArray(problem.conflicts) ? problem.conflicts : [],
    code: typeof problem.code === 'string' ? problem.code : undefined,
    requestId: headerRequestId ?? (typeof problem.requestId === 'string' ? problem.requestId : undefined),
  });
}

const SHOWN_CONFLICTS = 3;

/**
 * Повідомлення разом з першими конфліктами розкладу (`conflicts[]` у 409): «Календар не змінено…»
 * саме по собі не каже, яку пару й чому зачепило б.
 */
export function withConflicts(error: ApiError): string {
  if (error.conflicts.length === 0) return error.message;
  const shown = error.conflicts.slice(0, SHOWN_CONFLICTS).map((conflict) => conflict.message);
  const more = error.conflicts.length - shown.length;
  return [error.message, ...shown, more > 0 ? `І ще ${more}.` : ''].filter(Boolean).join(' ');
}

/** Текст для тосту/помилки з будь-якого винятку. */
export function errorMessage(error: unknown): string {
  if (isApiError(error)) return withConflicts(error);
  if (error instanceof Error && error.message) return error.message;
  return 'Щось пішло не так.';
}
