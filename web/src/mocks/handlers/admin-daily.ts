/**
 * Щоденна робота диспетчера (CONTRACTS §2, §4, §6): панель, відсутності, зачеплені пари, варіанти,
 * зміни з підтвердженням, розпорядження й файли. Стан і правила — у ./admin-daily/ (стан живе в памʼяті
 * до перезавантаження; демо-ранок будується на найближчий навчальний день).
 *
 * Додаткові поля до контракту (див. docs/CHANGELOG-CONTRACTS.md, розділ admin-daily): у зачеплених пар і
 * варіантів є `date`, у варіанта відпрацювання — `date` і `pair`; у перестановки `pairTo` — звідки пара.
 */
import { http, HttpResponse } from 'msw';
import type {
  AbsenceRequest,
  CreateChangeRequest,
  InternalChangeReplyRequest,
  IsoDate,
  UpdateChangeRequest,
  UpdateOrderNotesRequest,
} from '@/api/types';
import { compareIsoDates, isIsoDate, todayInKyiv } from '@/lib/date';
import { apiPath, badRequest, businessRule, conflict, notFound, requiredParam, readJson } from '../kit';
import {
  cancelAbsence,
  cancelChange,
  confirmByAdmin,
  createAbsence,
  createChange,
  getAbsence,
  replyToChange,
  resendConfirmation,
  updateAbsence,
  updateChange,
} from './admin-daily/change-commands';
import { dashboardFor } from './admin-daily/dashboard';
import { orderDocx, orderPdf, orderPng } from './admin-daily/documents';
import { findTemplateLesson } from './admin-daily/lessons';
import { draftFor, listOrders, publishOrder, updateNotes } from './admin-daily/order-commands';
import { nextOrderNumber } from './admin-daily/orders';
import { affectedLessons, optionsFor } from './admin-daily/rules';
import { getOrder, world } from './admin-daily/state';
import { changesOn, toChangeDto, toOrderDto } from './admin-daily/world';

const CHANGE_STATUSES = new Set(['DRAFT', 'PENDING_CONFIRMATION', 'CONFIRMED', 'DECLINED', 'CANCELLED', 'APPLIED']);
const FORMATS = {
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  pdf: 'application/pdf',
  png: 'image/png',
} as const;

function dateParam(url: URL, name: string): IsoDate {
  const value = requiredParam(url, name);
  if (!isIsoDate(value)) throw badRequest(`Параметр ${name} має бути датою РРРР-ММ-ДД.`, [{ field: name, message: 'Формат РРРР-ММ-ДД' }]);
  return value;
}

function optionalDate(url: URL, name: string): IsoDate | null {
  const value = url.searchParams.get(name);
  if (value === null || value === '') return null;
  if (!isIsoDate(value)) throw badRequest(`Параметр ${name} має бути датою РРРР-ММ-ДД.`, [{ field: name, message: 'Формат РРРР-ММ-ДД' }]);
  return value;
}

/** Номер основного розпорядження для доповнення (у файлі: «ДОПОВНЕННЯ №37 до розпорядження №35»). */
function parentNumber(parentId: string | null | undefined): number | null {
  return parentId ? (world().orders.get(parentId)?.number ?? null) : null;
}

export const handlers = [
  // ── Панель ──
  http.get(apiPath('/admin/dashboard'), ({ request }) => {
    const url = new URL(request.url);
    const date = optionalDate(url, 'date') ?? todayInKyiv();
    return HttpResponse.json(dashboardFor(world(), date, new Date()));
  }),

  // ── Відсутності ──
  http.get(apiPath('/admin/absences'), ({ request }) => {
    const url = new URL(request.url);
    const from = optionalDate(url, 'from');
    const to = optionalDate(url, 'to');
    if (from && to && compareIsoDates(to, from) < 0) {
      return badRequest('Дата to не може бути раніше за from.', [{ field: 'to', message: 'Не раніше за from' }]);
    }
    const teacherId = url.searchParams.get('teacherId');
    const list = [...world().absences.values()]
      .filter(
        (a) =>
          a.status === 'ACTIVE' &&
          (!teacherId || a.teacher.id === teacherId) &&
          (!from || compareIsoDates(a.dateTo, from) >= 0) &&
          (!to || compareIsoDates(a.dateFrom, to) <= 0),
      )
      .sort((a, b) => a.dateFrom.localeCompare(b.dateFrom) || (a.createdAt ?? '').localeCompare(b.createdAt ?? ''));
    return HttpResponse.json(list);
  }),

  http.post(apiPath('/admin/absences'), async ({ request }) => {
    const absence = createAbsence(await readJson<Partial<AbsenceRequest>>(request), new Date());
    return HttpResponse.json(absence, { status: 201, headers: { Location: `/api/admin/absences/${absence.id}` } });
  }),

  http.put(apiPath('/admin/absences/:id'), async ({ params, request }) =>
    HttpResponse.json(updateAbsence(String(params.id), await readJson<Partial<AbsenceRequest>>(request))),
  ),

  http.delete(apiPath('/admin/absences/:id'), ({ params }) => {
    cancelAbsence(String(params.id), new Date());
    return new HttpResponse(null, { status: 204 });
  }),

  http.get(apiPath('/admin/absences/:id/affected'), ({ params }) => {
    const absence = getAbsence(String(params.id));
    // `date` — поле, якого клієнт потребує, але core поки не надсилає (integration.md §5); мок його дає,
    // а клієнт без нього виводить дату лише для одноденної відсутності (withLessonDates)
    const items = affectedLessons(world(), absence).map((item) => ({
      date: item.date,
      lesson: item.lesson,
      decision: item.decision ? { changeId: item.decision.id, status: item.decision.status, kind: item.decision.kind } : null,
      // мок не моделює відсутніх замінників (core: AffectedLessons.substitutions)
      asSubstitute: false,
    }));
    return HttpResponse.json(items);
  }),

  // ── Варіанти для пари ──
  http.get(apiPath('/admin/lessons/:lessonId/options'), ({ params }) => {
    const found = findTemplateLesson(String(params.lessonId));
    if (!found) return notFound(`Заняття з id ${String(params.lessonId)} не знайдено.`);
    // як core: {lesson, options} без дати заняття (дату варіанти відпрацювання мають самі)
    return HttpResponse.json({ lesson: found.lesson, options: optionsFor(world(), found.date, found.lesson) });
  }),

  // ── Зміни ──
  http.get(apiPath('/admin/changes'), ({ request }) => {
    const url = new URL(request.url);
    const date = dateParam(url, 'date');
    const status = url.searchParams.get('status');
    if (status && !CHANGE_STATUSES.has(status)) {
      return badRequest('Невідомий стан зміни.', [{ field: 'status', message: 'Невідомий стан' }]);
    }
    const list = changesOn(world(), date)
      .filter((c) => !status || c.status === status)
      .sort((a, b) => a.pairFrom - b.pairFrom || a.group.code.localeCompare(b.group.code, 'uk') || a.createdAt.localeCompare(b.createdAt))
      .map(toChangeDto);
    return HttpResponse.json(list);
  }),

  http.post(apiPath('/admin/changes'), async ({ request }) => {
    const change = createChange(await readJson<Partial<CreateChangeRequest>>(request), new Date());
    return HttpResponse.json(toChangeDto(change), { status: 201, headers: { Location: `/api/admin/changes/${change.id}` } });
  }),

  http.put(apiPath('/admin/changes/:id'), async ({ params, request }) =>
    HttpResponse.json(toChangeDto(updateChange(String(params.id), await readJson<Partial<UpdateChangeRequest>>(request)))),
  ),

  http.delete(apiPath('/admin/changes/:id'), ({ params }) => {
    cancelChange(String(params.id), new Date());
    return new HttpResponse(null, { status: 204 });
  }),

  http.post(apiPath('/admin/changes/:id/request-confirmation'), ({ params }) =>
    HttpResponse.json(toChangeDto(resendConfirmation(String(params.id), new Date()))),
  ),

  http.post(apiPath('/admin/changes/:id/confirm'), ({ params }) =>
    HttpResponse.json(toChangeDto(confirmByAdmin(String(params.id), new Date()))),
  ),

  // Відповідь викладача з Telegram (core internal, роль SERVICE): так її передає бот
  http.post(apiPath('/internal/changes/:id/reply'), async ({ params, request }) => {
    const body = await readJson<Partial<InternalChangeReplyRequest>>(request);
    return HttpResponse.json(toChangeDto(replyToChange(String(params.id), body.teacherId, body.decision, body.comment, new Date())));
  }),

  // ── Розпорядження ──
  http.get(apiPath('/admin/orders'), ({ request }) => HttpResponse.json(listOrders(new URL(request.url)))),
  // як OrderNumberingService: наступний = max(останній + 1, перший); мок починає з 1
  http.get(apiPath('/admin/orders/numbering'), ({ request }) => {
    const semesterId = new URL(request.url).searchParams.get('semesterId') ?? '';
    const next = nextOrderNumber(world(), semesterId);
    return HttpResponse.json({ semesterId, firstNumber: 1, lastNumber: next > 1 ? next - 1 : null, nextNumber: next });
  }),

  http.get(apiPath('/admin/orders/draft'), ({ request }) => {
    const draft = draftFor(new URL(request.url).searchParams.get('date'), new Date());
    return HttpResponse.json(toOrderDto(draft));
  }),

  http.get(apiPath('/admin/orders/:id'), ({ params }) => HttpResponse.json(toOrderDto(getOrder(String(params.id))))),

  http.put(apiPath('/admin/orders/:id/notes'), async ({ params, request }) => {
    const body = await readJson<Partial<UpdateOrderNotesRequest>>(request);
    return HttpResponse.json(toOrderDto(updateNotes(String(params.id), body.notes, body.version)));
  }),

  // як core: чернетку відкидають, імпортоване паперове видаляють (без доповнень), опубліковане системою — 422
  http.delete(apiPath('/admin/orders/:id'), ({ params }) => {
    const order = getOrder(String(params.id));
    const amendments = [...world().orders.values()].filter((o) => o.parentId === order.id);
    if (order.status === 'PUBLISHED' && !order.imported) {
      return businessRule('ORDER_ALREADY_PUBLISHED', 'Опубліковане розпорядження видаляти не можна.');
    }
    if (amendments.length > 0) {
      return conflict(`До розпорядження №${order.number ?? '—'} є доповнення ${amendments.map((a) => `№${a.number ?? '—'}`).join(', ')}: спершу видаліть їх.`);
    }
    world().orders.delete(order.id);
    return new HttpResponse(null, { status: 204 });
  }),

  http.post(apiPath('/admin/orders/:id/publish'), ({ params }) =>
    HttpResponse.json(toOrderDto(publishOrder(String(params.id), new Date()))),
  ),

  // ── Файли розпорядження (notification) ──
  http.get(apiPath('/documents/orders/:file'), async ({ params }) => {
    const match = /^(.+)\.(docx|pdf|png)$/.exec(String(params.file));
    if (!match) return notFound('Формат має бути docx, pdf або png.');
    const [, id = '', format = 'pdf'] = match;
    const order = world().orders.get(id);
    if (!order || order.status !== 'PUBLISHED') return notFound('Документ буде доступний після публікації розпорядження.');
    const dto = toOrderDto(order);
    const parent = parentNumber(order.parentId);
    const bytes =
      format === 'docx' ? orderDocx(dto, parent) : format === 'pdf' ? await orderPdf(dto, parent) : await orderPng(dto, parent);
    const type = FORMATS[format as keyof typeof FORMATS];
    const name = `rozporiadzhennia-${order.number ?? 'chernetka'}-${order.date}.${format}`;
    return HttpResponse.arrayBuffer(bytes.slice().buffer, {
      headers: { 'Content-Type': type, 'Content-Disposition': `attachment; filename="${name}"` },
    });
  }),
];
