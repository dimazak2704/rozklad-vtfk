// @vitest-environment node
import { describe, expect, it } from 'vitest';
import type { AffectedLesson, ChangeOption } from '@/api/admin-daily';
import type { AbsenceDto, ChangeDto, DashboardDto, OrderSummaryDto } from '@/api/types';
import { buildStops, reachedCount } from './dashboard/morning';
import { pairsText } from './format';
import {
  buildFeed,
  defaultReason,
  filterFromParam,
  isObjection,
  isOpen,
  LESSON_STATE,
  lessonState,
  matchesFilter,
  noticeAnswer,
  optionsByKind,
  pendingInfo,
  pickLabel,
  requestsForOption,
  teacherAnswered,
  toEntries,
} from './model';

const lesson: AffectedLesson['lesson'] = {
  id: 'lesson-1',
  pair: 3,
  startsAt: '11:00',
  endsAt: '12:00',
  discipline: { id: 'd-1', shortName: 'Ін.мова', name: 'Іноземна мова', color: null },
  teacher: { id: 't-absent', shortName: 'Крисак Л.В.', displayName: 'Крисак Л.' },
  room: { id: 'r-110', name: '110', type: 'CLASSROOM' },
  group: { id: 'g-1m', code: '1М', course: 1 },
  subgroup: 1,
  kind: 'REGULAR',
  online: false,
  original: null,
  reasonText: null,
  changeId: null,
};

const option = (patch: Partial<ChangeOption>): ChangeOption => ({
  kind: 'REPLACE_TEACHER',
  score: 60,
  title: 'Прокопчук',
  reasons: [],
  warnings: [],
  teacher: { id: 't-new', shortName: 'Прокопчук', displayName: 'Прокопчук' },
  discipline: lesson.discipline,
  room: lesson.room,
  swapLesson: null,
  date: null,
  pair: null,
  ...patch,
});

const ctx = { date: '2026-11-09', lesson, absenceId: 'a-1', reasonCode: 'ABSENT' as const, reasonText: null };

describe('стан пари', () => {
  it('скасоване рішення — знову без рішення, відмова — потрібен інший варіант', () => {
    expect(lessonState(null)).toBe('uncovered');
    expect(lessonState({ status: 'CANCELLED', kind: 'REPLACE_TEACHER' })).toBe('uncovered');
    expect(lessonState({ status: 'DECLINED', kind: 'REPLACE_TEACHER' })).toBe('declined');
    expect(lessonState({ status: 'PENDING_CONFIRMATION', kind: 'REPLACE_TEACHER' })).toBe('pending');
    expect(lessonState({ status: 'CONFIRMED', kind: 'CANCEL' })).toBe('cancelled');
    expect(lessonState({ status: 'CONFIRMED', kind: 'SWAP' })).toBe('confirmed');
    expect(lessonState({ status: 'APPLIED', kind: 'REPLACE_TEACHER' })).toBe('applied');
    expect(isOpen('declined')).toBe(true);
    expect(isOpen('pending')).toBe(false);
  });

  it('відсутній замінник: неопублікована зміна потребує нового рішення, опублікована — ні', () => {
    expect(lessonState({ status: 'CONFIRMED', kind: 'REPLACE_TEACHER' }, true)).toBe('substituteAbsent');
    expect(lessonState({ status: 'PENDING_CONFIRMATION', kind: 'REPLACE_TEACHER' }, true)).toBe('substituteAbsent');
    expect(lessonState({ status: 'APPLIED', kind: 'REPLACE_TEACHER' }, true)).toBe('applied');
    expect(LESSON_STATE.substituteAbsent.needsAction).toBe(true);
    expect(matchesFilter('substituteAbsent', 'action')).toBe(true);
    expect(isOpen('substituteAbsent')).toBe(false);
  });

  it('фільтри з адреси панелі', () => {
    expect(filterFromParam('uncovered')).toBe('action');
    expect(filterFromParam('declined')).toBe('declined');
    expect(filterFromParam('щось')).toBe('all');
    expect(matchesFilter('declined', 'action')).toBe(true);
    expect(matchesFilter('applied', 'done')).toBe(true);
    expect(matchesFilter('pending', 'done')).toBe(false);
  });

  it('строк відповіді: скільки лишилося або наскільки прострочено', () => {
    const deadline = '2026-11-09T06:00:00.000Z';
    expect(pendingInfo(deadline, Date.parse('2026-11-09T05:49:00Z'))).toMatchObject({ overdue: false, minutes: 11, label: 'ще 11 хв' });
    expect(pendingInfo(deadline, Date.parse('2026-11-09T06:07:00Z'))).toMatchObject({ overdue: true, minutes: 7 });
    expect(pendingInfo(null, 0)).toBeNull();
  });

  it('причина за замовчуванням: відрядження — «Відр.», решта — «Відп.»', () => {
    expect(defaultReason('BUSINESS_TRIP')).toBe('BUSINESS_TRIP');
    expect(defaultReason('SICK_LEAVE')).toBe('ABSENT');
    expect(defaultReason(undefined)).toBe('ABSENT');
  });
});

describe('варіант → запити на сервер', () => {
  it('заміна викладача й іншою дисципліною', () => {
    expect(requestsForOption(option({}), ctx)).toEqual([
      expect.objectContaining({ kind: 'REPLACE_TEACHER', originalLessonId: 'lesson-1', newTeacherId: 't-new', pairFrom: 3, subgroup: 1, absenceId: 'a-1', reasonText: null }),
    ]);
    const other = { id: 'd-2', shortName: 'ІКТ', name: 'ІКТ', color: null };
    expect(requestsForOption(option({ kind: 'REPLACE_DISCIPLINE', discipline: other }), ctx)?.[0]).toMatchObject({ newDisciplineId: 'd-2' });
  });

  it('перестановка передає пізнішу пару, скасування — лише пару', () => {
    const swap = requestsForOption(option({ kind: 'SWAP', swapLesson: { ...lesson, id: 'lesson-5', pair: 5 } }), ctx);
    expect(swap?.[0]).toMatchObject({ kind: 'SWAP', swapLessonId: 'lesson-5' });
    expect(requestsForOption(option({ kind: 'CANCEL', teacher: null }), ctx)).toEqual([expect.objectContaining({ kind: 'CANCEL' })]);
  });

  it('відпрацювання — скасувати сьогодні й додати пару в інший день', () => {
    const requests = requestsForOption(option({ kind: 'ADDITIONAL', date: '2026-11-13', pair: 4, teacher: lesson.teacher }), ctx);
    expect(requests?.map((r) => r.kind)).toEqual(['CANCEL', 'ADDITIONAL']);
    expect(requests?.[1]).toMatchObject({ date: '2026-11-13', pairFrom: 4, newTeacherId: 't-absent', newDisciplineId: 'd-1', reasonCode: 'OTHER' });
  });

  it('неповний варіант не перетворюється на запит', () => {
    expect(requestsForOption(option({ teacher: null }), ctx)).toBeNull();
    expect(requestsForOption(option({ kind: 'ADDITIONAL', date: null }), ctx)).toBeNull();
    expect(requestsForOption(option({ kind: 'SWAP' }), ctx)).toBeNull();
  });

  it('текст причини йде лише з «Інше»', () => {
    expect(requestsForOption(option({}), { ...ctx, reasonCode: 'MUTUAL', reasonText: 'щось' })?.[0]?.reasonText).toBeNull();
    expect(requestsForOption(option({}), { ...ctx, reasonCode: 'OTHER', reasonText: 'Конф.' })?.[0]?.reasonText).toBe('Конф.');
  });

  it('кнопка вибору: «Призначити» в NOTIFY й AUTO, «Надіслати прохання» в CONFIRM, «Обрати» без викладача', () => {
    const teacher = option({});
    expect(pickLabel(teacher, 'NOTIFY')).toBe('Призначити');
    expect(pickLabel(teacher, 'AUTO')).toBe('Призначити');
    expect(pickLabel(teacher, undefined)).toBe('Призначити');
    expect(pickLabel(teacher, 'CONFIRM')).toBe('Надіслати прохання');
    expect(pickLabel(option({ kind: 'REPLACE_DISCIPLINE' }), 'CONFIRM')).toBe('Надіслати прохання');
    expect(pickLabel(option({ kind: 'CANCEL', teacher: null }), 'CONFIRM')).toBe('Обрати');
    expect(pickLabel(option({ kind: 'SWAP' }), 'NOTIFY')).toBe('Обрати');
  });

  it('групування за видами зберігає порядок сервера', () => {
    const grouped = optionsByKind([option({ title: 'А', score: 70 }), option({ kind: 'CANCEL', title: 'Пари не буде' }), option({ title: 'Б', score: 50 })]);
    expect(grouped.get('REPLACE_TEACHER')?.map((o) => o.title)).toEqual(['А', 'Б']);
    expect(grouped.get('CANCEL')).toHaveLength(1);
    expect(grouped.get('SWAP')).toEqual([]);
  });
});

describe('стрічка подій', () => {
  const change: ChangeDto = {
    id: 'c-1',
    kind: 'REPLACE_TEACHER',
    status: 'CONFIRMED',
    date: '2026-11-09',
    pairFrom: 2,
    pairTo: 2,
    group: { id: 'g', code: '1КІ2', course: 1 },
    subgroup: 1,
    original: { discipline: lesson.discipline, teacher: lesson.teacher, room: lesson.room },
    new: { discipline: lesson.discipline, teacher: { id: 't', shortName: 'Михальчук', displayName: 'Михальчук' }, room: lesson.room },
    reasonCode: 'ABSENT',
    reasonText: null,
    absenceId: null,
    confirmDeadline: null,
    respondedAt: '2026-11-09T05:15:00Z',
    orderId: null,
    approvalMode: 'CONFIRM',
    originalLessonId: lesson.id,
    swapLessonId: null,
    swapFromPair: null,
    printedReason: 'Відп.',
    responseComment: null,
    remindedAt: null,
    createdBy: 'admin',
    createdAt: '2026-11-09T05:00:00Z',
    version: 2,
    history: [
      { at: '2026-11-09T05:00:00Z', actor: 'admin', from: null, to: 'PENDING_CONFIRMATION', comment: null },
      { at: '2026-11-09T05:15:00Z', actor: 'Михальчук', from: 'PENDING_CONFIRMATION', to: 'CONFIRMED', comment: null },
    ],
  };
  const absence: AbsenceDto = {
    id: 'a-1',
    teacher: lesson.teacher as AbsenceDto['teacher'],
    dateFrom: '2026-11-09',
    dateTo: '2026-11-11',
    pairs: null,
    reasonCategory: 'SICK_LEAVE',
    comment: null,
    source: 'BOT',
    status: 'ACTIVE',
    createdBy: 'telegram-bot',
    createdAt: '2026-11-09T04:30:00Z',
    version: 0,
  };
  const order: OrderSummaryDto = { id: 'o-36', number: 36, date: '2026-11-09', kind: 'ORDER', status: 'PUBLISHED', itemCount: 3, publishedAt: '2026-11-09T05:30:00Z', imported: false };

  it('NOTIFY: призначення, «Добре» і заперечення після розпорядження', () => {
    const notice: ChangeDto = {
      ...change,
      id: 'c-2',
      status: 'APPLIED',
      approvalMode: 'NOTIFY',
      teacherAnswer: 'DECLINE',
      history: [
        { at: '2026-11-09T05:00:00Z', actor: 'admin', from: null, to: 'CONFIRMED', comment: null },
        { at: '2026-11-09T05:10:00Z', actor: 'Михальчук', from: 'CONFIRMED', to: 'CONFIRMED', comment: null },
        { at: '2026-11-09T05:20:00Z', actor: 'admin', from: 'CONFIRMED', to: 'APPLIED', comment: null },
        { at: '2026-11-09T05:40:00Z', actor: 'Михальчук', from: 'APPLIED', to: 'APPLIED', comment: null },
      ],
    };
    expect(buildFeed([notice], [], [], '2026-11-09').map((f) => [f.kind, f.text])).toEqual([
      ['declined', 'Не може, потрібне доповнення: Михальчук — 1КІ2, 2 пара (Ін.мова)'],
      ['confirmed', '«Добре»: Михальчук — 1КІ2, 2 пара (Ін.мова)'],
      ['confirmed', 'Призначено: Михальчук — 1КІ2, 2 пара (Ін.мова)'],
    ]);
  });

  it('NOTIFY: стан пари, відповідь викладача і живий тост', () => {
    const absenceRef = { id: 'a', teacher: lesson.teacher } as AbsenceDto;
    const item = (status: ChangeDto['status']): AffectedLesson =>
      ({ date: '2026-11-09', lesson, decision: { changeId: 'c-3', status, kind: 'REPLACE_TEACHER' }, asSubstitute: false });
    const notice = (patch: Partial<ChangeDto>): ChangeDto => ({ ...change, id: 'c-3', approvalMode: 'NOTIFY', teacherAnswer: null, ...patch });

    const objected = notice({ status: 'APPLIED', teacherAnswer: 'DECLINE' });
    expect(toEntries(absenceRef, [item('APPLIED')], new Map([['c-3', objected]]))[0]?.state).toBe('objected');
    expect(toEntries(absenceRef, [item('APPLIED')], new Map([['c-3', notice({ status: 'APPLIED' })]]))[0]?.state).toBe('applied');
    expect(isObjection(objected)).toBe(true);
    expect(LESSON_STATE.objected.needsAction).toBe(true);
    expect(matchesFilter('objected', 'action')).toBe(true);
    expect(matchesFilter('objected', 'declined')).toBe(true);
    expect(matchesFilter('objected', 'done')).toBe(false);

    expect(noticeAnswer(notice({ status: 'CONFIRMED' }))).toBe('waiting');
    expect(noticeAnswer(notice({ status: 'CONFIRMED', teacherAnswer: 'CONFIRM' }))).toBe('ok');
    expect(noticeAnswer(notice({ status: 'DECLINED', teacherAnswer: 'DECLINE' }))).toBe('cannot');
    expect(noticeAnswer({ ...change, approvalMode: 'CONFIRM' })).toBeNull();

    expect(teacherAnswered('PENDING_CONFIRMATION', 'CONFIRMED', 'CONFIRM')).toBe(true);
    expect(teacherAnswered('CONFIRMED', 'CONFIRMED', 'CONFIRM')).toBe(false);
    expect(teacherAnswered('CONFIRMED', 'DECLINED', 'NOTIFY')).toBe(true);
    expect(teacherAnswered('APPLIED', 'APPLIED', 'NOTIFY')).toBe(true);
    expect(teacherAnswered(null, 'CONFIRMED', 'NOTIFY')).toBe(false);
  });

  it('найновіші події першими, відповідь з Telegram названо прямо', () => {
    const feed = buildFeed([change], [absence], [order], '2026-11-09');
    expect(feed.map((f) => f.kind)).toEqual(['order', 'confirmed', 'request', 'absence']);
    expect(feed[1]?.text).toBe('Підтверджено в Telegram: Михальчук — 1КІ2, 2 пара (Ін.мова)');
    expect(feed[3]?.text).toBe('Відсутність: Крисак Л.В. (через Telegram-бота)');
    expect(feed[0]).toMatchObject({ sourceId: 'o-36', text: 'Розпорядження №36 опубліковано' });
  });
});

describe('маршрут ранку', () => {
  const dashboard: DashboardDto = {
    date: '2026-11-09',
    parity: 'NUMERATOR',
    absences: 3,
    uncoveredLessons: 4,
    pending: 2,
    confirmed: 2,
    declined: 1,
    orderDraft: { id: 'd', itemCount: 2 },
    generation: null,
    aiSpendToday: null,
    alerts: [],
  };

  it('поточна зупинка — перша з незробленою роботою', () => {
    const stops = buildStops(dashboard, null, '2026-11-09');
    expect(stops.map((s) => s.state)).toEqual(['done', 'current', 'ahead', 'ahead', 'ahead']);
    expect(stops[1]).toMatchObject({ value: '4', alarm: true, href: '/admin/changes?date=2026-11-09&filter=uncovered' });
    expect(stops[2]?.hint).toBe('і 1 відмова: потрібен інший');
    expect(reachedCount(stops)).toBe(1);
  });

  it('коли все закрито й опубліковано — весь маршрут пройдено', () => {
    const done = buildStops(
      { ...dashboard, uncoveredLessons: 0, pending: 0, declined: 0, orderDraft: { id: 'd', itemCount: 0 } },
      { id: 'o', number: 36, date: '2026-11-09', kind: 'ORDER', status: 'PUBLISHED', itemCount: 3, publishedAt: '2026-11-09T05:30:00Z', imported: false },
      '2026-11-09',
    );
    expect(done.every((s) => s.state === 'done')).toBe(true);
    expect(done[4]?.value).toBe('№36');
    expect(reachedCount(done)).toBe(5);
  });
});

describe('підписи', () => {
  it('пари: увесь день, одна, проміжок, кілька', () => {
    expect(pairsText(null)).toBe('увесь день');
    expect(pairsText([5])).toBe('5 пара');
    expect(pairsText([8, 7])).toBe('7–8 пари');
    expect(pairsText([1, 3, 4])).toBe('1, 3–4 пари');
  });
});
