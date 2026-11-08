/**
 * Команди розпоряджень: чернетка на дату, примітки, публікація з номером, архів із пошуком.
 * Публікація вносить у розклад саме ті зміни, що були в чернетці (що бачив диспетчер — те й публікує).
 */
import type { OrderStatus, Page, Uuid } from '@/api/types';
import { compareIsoDates, formatNumericDate, isIsoDate, isoWeekday } from '@/lib/date';
import { matchScore } from '@/lib/search';
import { findGroupByCode } from '../../fixtures';
import { emitServerEvent } from '../../events';
import { badRequest, businessRule, paginate, problem } from '../../kit';
import { currentSettings } from '../settings';
import { recordOrderDeliveries } from '../telegram';
import { applyChanges } from './change-commands';
import { buildDraft, isWithinActiveSemester, nextOrderNumber, orderTitle, toSummary } from './orders';
import { getOrder, world } from './state';
import { ADMIN_ACTOR, type OrderRecord } from './world';

const MAX_NOTES = 20;
const MAX_NOTE_LENGTH = 500;

export function draftFor(date: string | null, now: Date): OrderRecord {
  if (!isIsoDate(date)) throw badRequest('Вкажіть дату у форматі РРРР-ММ-ДД.', [{ field: 'date', message: 'Формат РРРР-ММ-ДД' }]);
  // як OrderCalendar.requireOrderDate у core
  if (isoWeekday(date) > 5) throw businessRule('NOT_A_STUDY_DAY', `${formatNumericDate(date)} — вихідний день: розпорядження складають на робочі дні.`);
  if (!isWithinActiveSemester(date)) {
    throw businessRule('DATE_OUTSIDE_SEMESTER', `Дата ${formatNumericDate(date)} не входить у жоден семестр.`);
  }
  return buildDraft(world(), date, currentSettings(), now);
}

/** PUT …/notes `{notes, version}` — як OrderDrafts.editNotes у core: без version → 400, застаріла → 409 `stale-data`. */
export function updateNotes(id: Uuid, notes: unknown, version: unknown): OrderRecord {
  const order = getOrder(id);
  if (typeof version !== 'number') {
    throw badRequest('Перевірте дані.', [{ field: 'version', message: 'Вкажіть версію чернетки (поле version з відповіді).' }]);
  }
  if (order.status === 'PUBLISHED') throw businessRule('ORDER_ALREADY_PUBLISHED', 'Опубліковане розпорядження змінювати не можна.');
  if (version !== order.version) {
    throw problem({
      status: 409,
      slug: 'stale-data',
      title: 'Дані змінилися',
      detail: 'Запис щойно змінив інший користувач. Оновіть сторінку та повторіть дію.',
    });
  }
  if (!Array.isArray(notes) || notes.some((note) => typeof note !== 'string')) {
    throw badRequest('Примітки мають бути списком рядків.', [{ field: 'notes', message: 'Список рядків' }]);
  }
  // як NotesRequest у core: кожна примітка непорожня (@NotBlank) і до 500 символів; зберігаються обрізаними
  const errors = (notes as string[]).flatMap((note, index) =>
    !note.trim()
      ? [{ field: `notes[${index}]`, message: 'Примітка не може бути порожньою' }]
      : note.length > MAX_NOTE_LENGTH
        ? [{ field: `notes[${index}]`, message: `Не довше ${MAX_NOTE_LENGTH} символів` }]
        : [],
  );
  const cleaned = (notes as string[]).map((note) => note.trim());
  if (cleaned.length > MAX_NOTES) errors.push({ field: 'notes', message: `Не більше ${MAX_NOTES} приміток` });
  if (errors.length > 0) throw badRequest('Перевірте примітки.', errors);
  const next = { ...order, notes: cleaned, version: order.version + 1 };
  world().orders.set(id, next);
  return next;
}

/** POST …/publish: номер = останній у семестрі + 1, зміни → APPLIED, події й розсилка. */
export function publishOrder(id: Uuid, now: Date): OrderRecord {
  const order = getOrder(id);
  if (order.status === 'PUBLISHED') throw businessRule('ORDER_ALREADY_PUBLISHED', 'Розпорядження вже опубліковане.');
  if (order.kind === 'AMENDMENT' && order.items.length + order.roomChanges.length === 0) {
    throw businessRule('AMENDMENT_EMPTY', 'У доповненні немає нових змін — публікувати нічого.');
  }
  const number = nextOrderNumber(world(), order.semesterId);
  const parentNumber = order.parentId ? (world().orders.get(order.parentId)?.number ?? null) : null;
  const published: OrderRecord = {
    ...order,
    number,
    title: orderTitle(order.kind, number, parentNumber),
    status: 'PUBLISHED',
    publishedAt: now.toISOString(),
    publishedBy: ADMIN_ACTOR,
    version: order.version + 1,
  };
  world().orders.set(id, published);

  const changeIds = [...order.items, ...order.roomChanges].flatMap((item) => (item.changeId ? [item.changeId] : []));
  const applied = applyChanges(changeIds, id, number, now);
  const groupIds = new Set<Uuid>();
  const teacherIds = new Set<Uuid>();
  for (const change of applied) {
    groupIds.add(change.group.id);
    for (const teacher of [change.original.teacher, change.new.teacher]) if (teacher) teacherIds.add(teacher.id);
  }
  // рядки без звʼязку зі зміною (примітки, імпортовані рядки) — групи знаходимо за кодом
  for (const item of order.items) {
    const group = findGroupByCode(item.groupCode);
    if (group) groupIds.add(group.id);
  }
  // core шле в SSE короткий запис розпорядження (як рядок архіву), а не весь документ
  emitServerEvent('order.published', toSummary(published));
  emitServerEvent('schedule.changed', {
    dates: [order.date],
    groupIds: [...groupIds],
    teacherIds: [...teacherIds],
    reason: 'ORDER_PUBLISHED',
  });
  recordOrderDeliveries(id, [...groupIds]);
  return published;
}

const STATUSES: readonly OrderStatus[] = ['DRAFT', 'PUBLISHED'];

/** Пошук в архіві: номер («35», «№35»), код групи («4ki1»), прізвище викладача, дисципліна. */
function matchesQuery(order: OrderRecord, query: string): boolean {
  const number = /^№?\s*(\d+)$/.exec(query.trim());
  if (number) return order.number === Number(number[1]);
  const texts = [...order.items, ...order.roomChanges].flatMap((item) => [
    item.groupCode,
    item.fromTeacher,
    item.toTeacher,
    item.fromDiscipline,
    item.toDiscipline,
  ]);
  return matchScore(query, ...texts) >= 60;
}

export function listOrders(url: URL): Page<ReturnType<typeof toSummary>> {
  const params = url.searchParams;
  const from = params.get('from');
  const to = params.get('to');
  const status = params.get('status');
  const query = params.get('q')?.trim() ?? '';
  const errors = [
    ...(from && !isIsoDate(from) ? [{ field: 'from', message: 'Формат РРРР-ММ-ДД' }] : []),
    ...(to && !isIsoDate(to) ? [{ field: 'to', message: 'Формат РРРР-ММ-ДД' }] : []),
    ...(status && !STATUSES.includes(status as OrderStatus) ? [{ field: 'status', message: 'DRAFT або PUBLISHED' }] : []),
  ];
  if (errors.length > 0) throw badRequest('Некоректні параметри пошуку розпоряджень.', errors);
  const [sortField = 'date', sortDirection = 'desc'] = (params.get('sort') ?? 'date,desc').split(',');
  const factor = sortDirection === 'asc' ? 1 : -1;
  const orders = [...world().orders.values()]
    .filter(
      (order) =>
        (!from || compareIsoDates(order.date, from) >= 0) &&
        (!to || compareIsoDates(order.date, to) <= 0) &&
        (!status || order.status === status) &&
        (!query || matchesQuery(order, query)),
    )
    .sort((a, b) => {
      const byNumber = (a.number ?? Number.MAX_SAFE_INTEGER) - (b.number ?? Number.MAX_SAFE_INTEGER);
      if (sortField === 'number') return factor * byNumber || factor * a.date.localeCompare(b.date);
      return factor * a.date.localeCompare(b.date) || factor * byNumber;
    })
    .map(toSummary);
  return paginate(orders, url);
}
