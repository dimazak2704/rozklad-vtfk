/**
 * Мок-API кабінету викладача (CONTRACTS §3) і коду привʼязки Telegram для викладача (CONTRACTS §6).
 * Користувач — демо-обліковий запис `krentsin`, повʼязаний з викладачем «Кренцін» (DOMAIN §12).
 * Запити на заміну беруться з демо-змін handlers/public.ts, тож підтверджена заміна видна і в публічному
 * розкладі групи (щойно «опубліковано» розпорядження на той день).
 */
import { http, HttpResponse } from 'msw';
import type {
  AbsenceDto,
  AbsenceReasonCategory,
  ChangeReplyRequest,
  ChangeStatus,
  IsoDate,
  MeDto,
  MyAbsenceRequest,
  PreferenceDto,
  PreferenceKind,
  Uuid,
} from '@/api/types';
import { addDays, compareIsoDates, formatDateRange, isIsoDate, todayInKyiv } from '@/lib/date';
import { emitServerEvent } from '../events';
import { bellSlots, buildSchedule, isStudyDay, scheduleRangeError, SEMESTERS } from '../fixtures';
import { mockId } from '../ids';
import { apiPath, badRequest, businessRule, notFound, problem, readJson, requiredParam } from '../kit';
import { createMockStore } from '../store';
import { changesForTeacher, KRENTSIN, publicOverlay, replyToChange } from './public';

/** sub демо-користувача krentsin у realm (фіксовані id, ARCHITECTURE §6 / foundation). */
export const ME: MeDto = {
  userId: 'a0000000-0000-4000-8000-000000000002',
  username: 'krentsin',
  roles: ['TEACHER'],
  teacher: KRENTSIN,
};

const REASONS: readonly AbsenceReasonCategory[] = ['SICK_LEAVE', 'VACATION', 'BUSINESS_TRIP', 'PERSONAL', 'OTHER'];
const KINDS: readonly PreferenceKind[] = ['UNWANTED', 'UNAVAILABLE'];
const COMMENT_MAX = 500;

// ───────────────────────── Відсутності ─────────────────────────

/** n-й навчальний день від дати (n може бути відʼємним) або null, якщо вийшли за семестр. */
function studyDayOffset(date: IsoDate, n: number): IsoDate | null {
  let cursor = date;
  let left = Math.abs(n);
  for (let guard = 0; left > 0 && guard < 120; guard += 1) {
    cursor = addDays(cursor, Math.sign(n));
    if (isStudyDay(cursor)) left -= 1;
  }
  return left === 0 ? cursor : null;
}

/**
 * ДЕМО-відсутності Кренціна відносно «сьогодні»: минула (лікарняний, повідомлена через бота) і майбутня
 * (особисті обставини, дві пари) — її можна видалити в кабінеті. Сіється при першому зверненні.
 */
const absenceState = createMockStore<AbsenceDto[] | null>(() => null);

function absencesAt(now: Date): AbsenceDto[] {
  const existing = absenceState.get();
  if (existing) return existing;
  const today = todayInKyiv(now);
  const past = studyDayOffset(today, -12);
  const future = studyDayOffset(today, 6);
  const seeded: AbsenceDto[] = [];
  if (future) {
    seeded.push({
      id: mockId('absence', `krentsin-future-${future}`),
      teacher: KRENTSIN,
      dateFrom: future,
      dateTo: future,
      pairs: [3, 4],
      reasonCategory: 'PERSONAL',
      comment: null,
      source: 'WEB',
      status: 'ACTIVE',
      createdAt: now.toISOString(),
    });
  }
  if (past) {
    const pastEnd = studyDayOffset(past, 1) ?? past;
    seeded.push({
      id: mockId('absence', `krentsin-past-${past}`),
      teacher: KRENTSIN,
      dateFrom: past,
      dateTo: pastEnd,
      pairs: null,
      reasonCategory: 'SICK_LEAVE',
      comment: null,
      source: 'BOT',
      status: 'ACTIVE',
      createdAt: new Date(now.getTime() - 14 * 86_400_000).toISOString(),
    });
  }
  absenceState.set(seeded);
  return seeded;
}

function validateAbsence(body: Partial<MyAbsenceRequest>, today: IsoDate): Response | null {
  const errors: Array<{ field: string; message: string }> = [];
  if (!isIsoDate(body.dateFrom)) errors.push({ field: 'dateFrom', message: 'Вкажіть дату початку' });
  if (!isIsoDate(body.dateTo)) errors.push({ field: 'dateTo', message: 'Вкажіть дату завершення' });
  if (isIsoDate(body.dateFrom) && isIsoDate(body.dateTo) && compareIsoDates(body.dateTo, body.dateFrom) < 0) {
    errors.push({ field: 'dateTo', message: 'Не раніше за дату початку' });
  }
  const pairs = body.pairs;
  if (pairs !== null && pairs !== undefined) {
    const valid =
      Array.isArray(pairs) &&
      pairs.length > 0 &&
      pairs.every((p) => Number.isInteger(p) && p >= 1 && p <= 8) &&
      new Set(pairs).size === pairs.length;
    if (!valid) errors.push({ field: 'pairs', message: 'Оберіть пари від 1 до 8 або «увесь день»' });
  }
  if (!body.reasonCategory || !REASONS.includes(body.reasonCategory)) {
    errors.push({ field: 'reasonCategory', message: 'Оберіть причину' });
  }
  if (typeof body.comment === 'string' && body.comment.length > COMMENT_MAX) {
    errors.push({ field: 'comment', message: `Не більше ${COMMENT_MAX} символів` });
  }
  if (errors.length > 0) return badRequest('Перевірте дані відсутності.', errors);
  if (compareIsoDates(body.dateFrom as IsoDate, today) < 0) {
    return businessRule('ABSENCE_IN_PAST', 'Відсутність можна повідомити лише на сьогодні чи майбутні дати.');
  }
  return null;
}

/** Діапазони дат перетинаються, а пари — теж (null = увесь день). */
function overlaps(a: Pick<AbsenceDto, 'dateFrom' | 'dateTo' | 'pairs'>, b: Pick<AbsenceDto, 'dateFrom' | 'dateTo' | 'pairs'>): boolean {
  const datesOverlap = compareIsoDates(a.dateFrom, b.dateTo) <= 0 && compareIsoDates(b.dateFrom, a.dateTo) <= 0;
  if (!datesOverlap) return false;
  if (!a.pairs || !b.pairs) return true;
  return a.pairs.some((pair) => b.pairs?.includes(pair));
}

// ───────────────────────── Побажання ─────────────────────────

/** ДЕМО-побажання Кренціна на активний семестр: рання понеділкова пара небажана, пʼятниця після обіду — теж. */
const preferenceState = createMockStore<Map<Uuid, PreferenceDto[]>>(() => {
  const active = SEMESTERS.find((s) => s.active);
  return new Map(
    active
      ? [
          [
            active.id,
            [
              { weekday: 1, pair: 1, kind: 'UNWANTED', note: null },
              { weekday: 5, pair: 5, kind: 'UNWANTED', note: null },
              { weekday: 5, pair: 6, kind: 'UNAVAILABLE', note: null },
            ],
          ],
        ]
      : [],
  );
});

function semesterFrom(url: URL): Uuid {
  const semesterId = requiredParam(url, 'semesterId');
  if (!SEMESTERS.some((s) => s.id === semesterId)) throw notFound(`Семестр з id ${semesterId} не знайдено.`);
  return semesterId;
}

function validatePreferences(body: unknown): PreferenceDto[] | Response {
  if (!Array.isArray(body)) return badRequest('Тіло запиту — масив побажань [{weekday, pair, kind, note}].');
  const seen = new Set<string>();
  const result: PreferenceDto[] = [];
  for (const [index, item] of (body as Array<Partial<PreferenceDto>>).entries()) {
    const weekday = item.weekday;
    const pair = item.pair;
    const pairsThatDay = weekday && weekday >= 1 && weekday <= 5 ? bellSlots(weekday).length : 0;
    if (!weekday || pairsThatDay === 0) return badRequest('Некоректний день.', [{ field: `[${index}].weekday`, message: 'День від 1 (пн) до 5 (пт)' }]);
    if (!Number.isInteger(pair) || !pair || pair < 1 || pair > pairsThatDay) {
      return badRequest('Некоректна пара.', [{ field: `[${index}].pair`, message: `Цього дня є пари 1–${pairsThatDay}` }]);
    }
    if (!item.kind || !KINDS.includes(item.kind)) {
      return badRequest('Некоректний тип побажання.', [{ field: `[${index}].kind`, message: 'UNWANTED або UNAVAILABLE' }]);
    }
    const key = `${weekday}:${pair}`;
    if (seen.has(key)) return badRequest('Одна пара вказана двічі.', [{ field: `[${index}]`, message: 'Пара повторюється' }]);
    seen.add(key);
    const note = typeof item.note === 'string' && item.note.trim() ? item.note.trim().slice(0, 200) : null;
    result.push({ weekday, pair, kind: item.kind, note });
  }
  return result.sort((a, b) => a.weekday - b.weekday || a.pair - b.pair);
}

// ───────────────────────── Telegram ─────────────────────────

// ───────────────────────── Обробники ─────────────────────────

function parseStatuses(url: URL): ChangeStatus[] | null {
  const raw = url.searchParams.getAll('status').flatMap((value) => value.split(','));
  const statuses = raw.map((s) => s.trim()).filter(Boolean) as ChangeStatus[];
  return statuses.length > 0 ? statuses : null;
}

async function reply(id: string, request: Request, decision: 'CONFIRM' | 'DECLINE'): Promise<Response> {
  const body = await readJson<ChangeReplyRequest | null>(request);
  const comment = typeof body?.comment === 'string' && body.comment.trim() ? body.comment.trim() : null;
  if (comment && comment.length > COMMENT_MAX) {
    return badRequest('Коментар задовгий.', [{ field: 'comment', message: `Не більше ${COMMENT_MAX} символів` }]);
  }
  const updated = replyToChange(id, KRENTSIN.id, decision, comment, ME.username, new Date());
  emitServerEvent('change.updated', updated);
  return HttpResponse.json(updated);
}

export const handlers = [
  http.get(apiPath('/me'), () => HttpResponse.json(ME)),

  http.get(apiPath('/me/schedule'), ({ request }) => {
    const url = new URL(request.url);
    const from = url.searchParams.get('from');
    const to = url.searchParams.get('to');
    const rangeError = scheduleRangeError(from, to);
    if (rangeError || !from || !to) {
      return badRequest(rangeError ?? 'Вкажіть дати.', [
        { field: 'from', message: 'Формат РРРР-ММ-ДД, не пізніше за to' },
        { field: 'to', message: 'Не більше 31 дня від from' },
      ]);
    }
    return HttpResponse.json(buildSchedule('TEACHER', KRENTSIN.id, from, to, { overlay: publicOverlay(new Date()) }));
  }),

  http.get(apiPath('/me/changes'), ({ request }) => {
    const statuses = parseStatuses(new URL(request.url));
    const changes = changesForTeacher(KRENTSIN.id, new Date());
    return HttpResponse.json(statuses ? changes.filter((c) => statuses.includes(c.status)) : changes);
  }),

  http.post(apiPath('/me/changes/:id/confirm'), ({ params, request }) => reply(String(params.id), request, 'CONFIRM')),
  http.post(apiPath('/me/changes/:id/decline'), ({ params, request }) => reply(String(params.id), request, 'DECLINE')),

  http.get(apiPath('/me/absences'), () =>
    HttpResponse.json(
      [...absencesAt(new Date())]
        .filter((a) => a.status === 'ACTIVE')
        .sort((a, b) => compareIsoDates(b.dateFrom, a.dateFrom)),
    ),
  ),

  http.post(apiPath('/me/absences'), async ({ request }) => {
    const now = new Date();
    const body = await readJson<Partial<MyAbsenceRequest>>(request);
    const invalid = validateAbsence(body, todayInKyiv(now));
    if (invalid) return invalid;
    const request_ = body as MyAbsenceRequest;
    const list = absencesAt(now);
    const clash = list.find((a) => a.status === 'ACTIVE' && overlaps(a, { ...request_, pairs: request_.pairs ?? null }));
    if (clash) {
      return problem({ status: 409, detail: `На ${formatDateRange(clash.dateFrom, clash.dateTo)} відсутність уже повідомлено.` });
    }
    const absence: AbsenceDto = {
      id: mockId('absence', `krentsin-${crypto.randomUUID()}`),
      teacher: KRENTSIN,
      dateFrom: request_.dateFrom,
      dateTo: request_.dateTo,
      pairs: request_.pairs ? [...request_.pairs].sort((a, b) => a - b) : null,
      reasonCategory: request_.reasonCategory,
      comment: request_.comment?.trim() ? request_.comment.trim() : null,
      source: 'WEB',
      status: 'ACTIVE',
      createdAt: now.toISOString(),
    };
    absenceState.set([absence, ...list]);
    // у події немає причини й коментаря: вони приватні (DOMAIN §7)
    emitServerEvent('absence.created', { ...absence, comment: null });
    return HttpResponse.json(absence, { status: 201 });
  }),

  http.delete(apiPath('/me/absences/:id'), ({ params }) => {
    const now = new Date();
    const list = absencesAt(now);
    const absence = list.find((a) => a.id === params.id && a.status === 'ACTIVE');
    if (!absence) return notFound(`Відсутність з id ${String(params.id)} не знайдено серед ваших.`);
    if (compareIsoDates(absence.dateFrom, todayInKyiv(now)) <= 0) {
      return businessRule('ABSENCE_NOT_FUTURE', 'Видалити можна лише майбутню відсутність. Якщо плани змінилися, повідомте диспетчера.');
    }
    absenceState.set(list.filter((a) => a.id !== absence.id));
    return new HttpResponse(null, { status: 204 });
  }),

  http.get(apiPath('/me/preferences'), ({ request }) => {
    const semesterId = semesterFrom(new URL(request.url));
    return HttpResponse.json(preferenceState.get().get(semesterId) ?? []);
  }),

  http.put(apiPath('/me/preferences'), async ({ request }) => {
    const semesterId = semesterFrom(new URL(request.url));
    const parsed = validatePreferences(await readJson<unknown>(request));
    if (parsed instanceof Response) return parsed;
    preferenceState.update((current) => new Map(current).set(semesterId, parsed));
    return HttpResponse.json(parsed);
  }),

];
