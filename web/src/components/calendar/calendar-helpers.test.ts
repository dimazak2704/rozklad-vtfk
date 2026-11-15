import { describe, expect, it } from 'vitest';
import type { BellProfileSlotDto } from '@/api/types';
import { BELL_PROFILES } from '@/mocks/fixtures';
import { breakAfter, gridFromSlots, hasErrors, mostCommon, nextSlot, normalizeTime, slotsFromGrid, validateDay } from './bells';
import { isoToText, textToIso } from './dateText';
import { composeOrderNote, orderDate, renderNoteTemplate, T_DAYOFF, T_INFO, T_SUSPEND, type NoteInput } from './notes';
import { periodAudience, periodKindLabel, periodKindOf, periodRequest } from './periods';

describe('примітка періоду для розпорядження', () => {
  const FN = '{хто} з {від}р. по {до} {режим}.';
  const base: NoteInput = {
    template: T_SUSPEND,
    kindName: 'Практика',
    title: 'Електрорадіомонтажна практика',
    startsOn: '2026-10-19',
    endsOn: '2026-10-30',
    scope: 'GROUPS',
    courses: [],
    groupCodes: ['2КІ3'],
    studyMode: null,
    roomName: null,
  };

  // спільні вектори з контракту: так само шаблон рендерить importer
  it.each<[string, Partial<NoteInput>, string]>([
    ['T_SUSPEND, практика 2КІ3', {}, '2КІ3 – Електрорадіомонтажна практика з 19.10.26р. по 30.10.26 р.'],
    [
      'T_SUSPEND, НС 4КІ1 з аудиторією',
      { title: 'Основи національного спротиву', startsOn: '2026-11-16', endsOn: '2026-11-27', groupCodes: ['4КІ1'], roomName: '401' },
      '4КІ1 – Основи національного спротиву з 16.11.26р. по 27.11.26 р., ауд. 401',
    ],
    [
      'T_DAYOFF, зимові канікули для всіх',
      { template: T_DAYOFF, title: 'Зимові канікули', startsOn: '2026-12-28', endsOn: '2026-12-30', scope: 'ALL', groupCodes: [] },
      'Усі групи: зимові канікули з 28.12.26р. по 30.12.26 р.',
    ],
    [
      'ФН, дистанційно для трьох груп',
      { template: FN, title: 'Дистанційне навчання', startsOn: '2026-09-01', endsOn: '2026-10-31', groupCodes: ['4КІ1', '4КІ2', '4КІ3'], studyMode: 'ONLINE' },
      'Групи 4КІ1, 4КІ2, 4КІ3 з 01.09.26р. по 31.10.26 навчаються – дистанційно.',
    ],
  ])('%s', (_name, patch, expected) => {
    const input = { ...base, ...patch };
    expect(renderNoteTemplate(input.template ?? T_SUSPEND, input).text).toBe(expected);
    expect(composeOrderNote(input)).toBe(expected);
  });

  it('необовʼязкова частина зникає без аудиторії, невідоме поле лишається, курси — за зростанням', () => {
    expect(composeOrderNote({ ...base, roomName: null })).toBe('2КІ3 – Електрорадіомонтажна практика з 19.10.26р. по 30.10.26 р.');
    expect(renderNoteTemplate('{хто} {інше} [({вид})]', base).text).toBe('2КІ3 {інше} (Практика)');
    expect(composeOrderNote({ ...base, template: T_INFO, scope: 'COURSES', courses: [2, 1], groupCodes: [] })).toBe(
      '1, 2 курси – Електрорадіомонтажна практика з 19.10.26р. по 30.10.26 р.',
    );
    expect(composeOrderNote({ ...base, template: '{вид}: {назва_мала}', title: '' })).toBe('Практика: занять немає');
  });

  it('вид без шаблону складає примітку шаблоном «група не на парах»', () => {
    expect(composeOrderNote({ ...base, template: null, roomName: '401' })).toBe('2КІ3 – Електрорадіомонтажна практика з 19.10.26р. по 30.10.26 р., ауд. 401');
  });

  it('не складає неповну примітку', () => {
    expect(composeOrderNote({ ...base, groupCodes: [] })).toBe('');
    expect(composeOrderNote({ ...base, startsOn: '' })).toBe('');
    expect(composeOrderNote({ ...base, title: '' })).toBe('');
    expect(composeOrderNote({ ...base, template: FN, groupCodes: ['4КІ1'], studyMode: null })).toBe('');
  });

  it('канікули для всіх і дата з двома цифрами року', () => {
    expect(orderDate('2026-01-05')).toBe('05.01.26');
    expect(composeOrderNote({ ...base, template: T_DAYOFF, title: 'Осінні канікули', startsOn: '2026-10-26', endsOn: '2026-10-30', scope: 'ALL', groupCodes: [] })).toBe(
      'Усі групи: осінні канікули з 26.10.26р. по 30.10.26 р.',
    );
  });

  it('кого стосується період', () => {
    expect(periodAudience({ scope: 'COURSES', courses: [3], groups: [] })).toBe('3 курс');
    expect(periodAudience({ scope: 'COURSES', courses: [4, 3], groups: [] })).toBe('3, 4 курси');
    const groups = ['1КІ1', '1КІ2', '1КІ3', '1ТК1', '1ТК2'].map((code, i) => ({ id: String(i), code, course: 1 }));
    expect(periodAudience({ scope: 'GROUPS', courses: null, groups })).toBe('1КІ1, 1КІ2, 1КІ3 та ще 2');
    expect(periodAudience({ scope: 'ALL', courses: null, groups: [] })).toBe('Усі групи');
  });

  it('вид періоду: з довідника, а від старішого core — вбудований за старим kind', () => {
    const ons = { id: 'k-ns', code: 'НС', name: 'Національний спротив', color: null, effect: 'SUSPEND' as const };
    expect(periodKindOf({ kind: 'PRACTICE', periodKind: ons })).toBe(ons);
    expect(periodKindOf({ kind: 'EXAM_SESSION' })).toMatchObject({ code: 'І', name: 'Сесія', effect: 'SUSPEND', color: null });
    expect(periodKindOf({ kind: 'HOLIDAY' })).toMatchObject({ code: 'К', effect: 'DAY_OFF' });
    expect(periodKindLabel({ kind: 'STUDY_MODE', studyMode: 'ONLINE' })).toBe('Дистанційно');
    expect(periodKindLabel({ kind: 'PRACTICE', periodKind: ons, studyMode: null })).toBe('Національний спротив');
  });

  it('тіло PUT для журналу: kindId, аудиторія й керівники лише для «група не на парах»', () => {
    const period = {
      id: 'p-1',
      semesterId: 's-1',
      kind: 'PRACTICE' as const,
      kindId: 'k-ns',
      periodKind: { id: 'k-ns', code: 'НС', name: 'Національний спротив', color: null, effect: 'SUSPEND' as const },
      title: 'Основи національного спротиву',
      startsOn: '2026-11-16',
      endsOn: '2026-11-27',
      studyMode: null,
      orderNote: null,
      scope: 'GROUPS' as const,
      courses: null,
      groupIds: ['g-1'],
      groups: [{ id: 'g-1', code: '4КІ1', course: 4 }],
      roomId: 'r-401',
      supervisorIds: ['t-1'],
      version: 3,
    };
    expect(periodRequest(period)).toMatchObject({ kindId: 'k-ns', roomId: 'r-401', supervisorIds: ['t-1'], version: 3 });
    expect(periodRequest(period)).not.toHaveProperty('kind');
    const info = { ...period, periodKind: { ...period.periodKind, effect: 'INFO' as const } };
    expect(periodRequest(info)).toMatchObject({ roomId: null, supervisorIds: [] });
    // старіший core без довідника: вид — старим enum
    expect(periodRequest({ ...period, kindId: undefined, periodKind: undefined })).toMatchObject({ kind: 'PRACTICE' });
  });
});

describe('редактор дзвінків', () => {
  const active = BELL_PROFILES[0] as { slots: BellProfileSlotDto[] };

  it('бачить однакові пн–чт і коротку пʼятницю профілю «2 зміни, 60 хв»', () => {
    const grid = gridFromSlots(active.slots);
    expect(grid.sameMonThu).toBe(true);
    expect(grid.days[1]).toHaveLength(8);
    expect(grid.days[5]).toHaveLength(6);
    expect(grid.days[5][3]).toEqual({ pair: 4, startsAt: '12:20', endsAt: '13:20' });
  });

  it('у режимі «пн–чт однакові» копіює понеділок на вт–чт і нумерує пари підряд', () => {
    const grid = gridFromSlots(active.slots);
    const edited = { ...grid, days: { ...grid.days, 1: grid.days[1].map((s, i) => (i === 0 ? { ...s, startsAt: '08:00' } : s)) } };
    const slots = slotsFromGrid(edited);
    expect(slots.filter((s) => s.weekday === 4 && s.pair === 1)[0]?.startsAt).toBe('08:00');
    expect(slots.filter((s) => s.weekday === 5 && s.pair === 1)[0]?.startsAt).toBe('08:30');
    expect(slots).toHaveLength(8 * 4 + 6);
  });

  it('знаходить помилки часу: кінець раніше початку, накладання на попередню пару, порожні поля', () => {
    const errors = validateDay([
      { pair: 1, startsAt: '08:30', endsAt: '09:30' },
      { pair: 2, startsAt: '09:20', endsAt: '10:20' },
      { pair: 3, startsAt: '11:00', endsAt: '10:00' },
      { pair: 4, startsAt: '', endsAt: '12:00' },
    ]);
    expect(errors.get(0)).toBeUndefined();
    expect(errors.get(1)).toBe('Починається раніше, ніж закінчується 1 пара');
    expect(errors.get(2)).toBe('Пара закінчується раніше, ніж починається');
    expect(errors.get(3)).toBe('Вкажіть початок і кінець пари');
    expect(hasErrors({ sameMonThu: true, days: { 1: [], 2: [], 3: [], 4: [], 5: [{ pair: 1, startsAt: '08:30', endsAt: '09:30' }] } })).toBe(true);
  });

  it('рахує перерви й пропонує наступну пару з тією самою тривалістю', () => {
    const day = [
      { pair: 1, startsAt: '08:30', endsAt: '09:30' },
      { pair: 2, startsAt: '09:40', endsAt: '10:40' },
    ];
    expect(breakAfter(day, 0)).toBe(10);
    expect(breakAfter(day, 1)).toBeNull();
    expect(nextSlot(day)).toEqual({ pair: 3, startsAt: '10:50', endsAt: '11:50' });
    expect(nextSlot([])).toEqual({ pair: 1, startsAt: '08:30', endsAt: '09:30' });
  });
});

describe('час дзвінків текстом', () => {
  it('приводить набране до «HH:MM» у 24 години', () => {
    expect(normalizeTime('830')).toBe('08:30');
    expect(normalizeTime('8:30')).toBe('08:30');
    expect(normalizeTime('1730')).toBe('17:30');
    expect(normalizeTime('8')).toBe('08:00');
    expect(normalizeTime('17.05')).toBe('17:05');
    expect(normalizeTime('08:30')).toBe('08:30');
  });

  it('не вигадує час із неправильного', () => {
    expect(normalizeTime('25:00')).toBeNull();
    expect(normalizeTime('8:75')).toBeNull();
    expect(normalizeTime('ab')).toBeNull();
    expect(normalizeTime('')).toBeNull();
  });

  it('типова перерва — найчастіша', () => {
    expect(mostCommon([10, 20, 10, null])).toBe(10);
    expect(mostCommon([null])).toBeNull();
  });
});

describe('дата текстом', () => {
  it('показує «дд.мм.рррр» і читає набране день-місяць-рік', () => {
    expect(isoToText('2026-11-11')).toBe('11.11.2026');
    expect(isoToText('')).toBe('');
    expect(textToIso('11.11.2026')).toBe('2026-11-11');
    expect(textToIso('1.9.26')).toBe('2026-09-01');
    expect(textToIso('11/11/2026')).toBe('2026-11-11');
    expect(textToIso('2026-11-11')).toBe('2026-11-11');
  });

  it('неіснуюча дата чи не дата — null', () => {
    expect(textToIso('31.02.2026')).toBeNull();
    expect(textToIso('завтра')).toBeNull();
    expect(textToIso('')).toBeNull();
  });
});
