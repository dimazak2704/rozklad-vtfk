/**
 * Розпорядження (DOMAIN §8): рядки з підтверджених змін, примітки з календарних періодів, нумерація
 * в межах семестру й опубліковане розпорядження №35 (seed/orders/order-035-2026-10-19.jpg).
 */
import type {
  IsoDate,
  OrderItemDto,
  OrderKind,
  OrderRowType,
  OrderSummaryDto,
  SettingsDto,
  TeacherRef,
  Uuid,
  WeekParity,
} from '@/api/types';
import { compareIsoDates, formatLongDate } from '@/lib/date';
import { REASON_CODE_LABEL } from '@/lib/labels';
import { ACTIVE_SEMESTER, CALENDAR_PERIODS, parityOn, weekdayLabel } from '../../fixtures';
import { mockId } from '../../ids';
import { changesOn, type ChangeRecord, type OrderRecord, type World } from './world';

/** Рядок під заголовком, однаковий в усіх розпорядженнях коледжу (DOMAIN §8; core: OrderTexts.SUBTITLE). */
export const ORDER_SUBTITLE = 'По коледжу про деякі зміни в розкладі занять';

/**
 * Заголовок як у core (OrderTexts): «РОЗПОРЯДЖЕННЯ» (чернетка), «РОЗПОРЯДЖЕННЯ №35»,
 * «ДОПОВНЕННЯ до розпорядження №35» (власний номер доповнення — у полі `number`).
 */
export function orderTitle(kind: OrderKind, number: number | null, parentNumber: number | null): string {
  if (kind === 'AMENDMENT') return `ДОПОВНЕННЯ до розпорядження №${parentNumber ?? ''}`;
  return number === null ? 'РОЗПОРЯДЖЕННЯ' : `РОЗПОРЯДЖЕННЯ №${number}`;
}

/** «На 19 жовтня 2026 р.» (core: OrderTexts.dateLine). */
export function orderDateLabel(date: IsoDate): string {
  return `На ${formatLongDate(date)} р.`;
}

const PARITY_UPPER: Record<WeekParity, string> = { NUMERATOR: 'ЧИСЕЛЬНИК', DENOMINATOR: 'ЗНАМЕННИК' };
const UK = new Intl.Collator('uk', { numeric: true });

/** Причина так, як її друкують: «Відп.», «Зв.зам.»; для «Інше» — короткий текст диспетчера. */
export function reasonLabel(change: Pick<ChangeRecord, 'reasonCode' | 'reasonText'>): string {
  if (change.reasonCode === 'OTHER') return change.reasonText?.trim() || REASON_CODE_LABEL.OTHER;
  return REASON_CODE_LABEL[change.reasonCode];
}

/**
 * Імʼя викладача в рядку. «(п.)» — пара лише однієї підгрупи (DOMAIN §8); у розпорядженнях коледжу її
 * ставлять там, де це важливо студентам: коли скасовано пару однієї підгрупи («Крисак Л. (п.)», №35).
 */
function teacherText(teacher: TeacherRef | null | undefined, subgroupMark = false): string | null {
  if (!teacher) return null;
  return subgroupMark ? `${teacher.displayName} (п.)` : teacher.displayName;
}

function pairLabelOf(from: number, to: number | null): string {
  return to !== null && to !== from ? `${from}-${to}` : String(from);
}

interface RowInput {
  key: string;
  groupCode: string;
  pairLabel: string;
  rowType: OrderRowType;
  fromDiscipline?: string | null;
  fromTeacher?: string | null;
  reasonText?: string | null;
  toDiscipline?: string | null;
  toTeacher?: string | null;
  roomText?: string | null;
  changeId?: Uuid | null;
}

function row(input: RowInput): OrderItemDto {
  return {
    id: mockId('order-item', input.key),
    groupCode: input.groupCode,
    pairLabel: input.pairLabel,
    rowType: input.rowType,
    fromDiscipline: input.fromDiscipline ?? null,
    fromTeacher: input.fromTeacher ?? null,
    reasonText: input.reasonText ?? null,
    toDiscipline: input.toDiscipline ?? null,
    toTeacher: input.toTeacher ?? null,
    roomText: input.roomText ?? null,
    changeId: input.changeId ?? null,
  };
}

/** Рядки розпорядження для однієї зміни (перестановка дає два: нова пара і звільнена). */
function rowsOf(change: ChangeRecord): { items: OrderItemDto[]; roomChanges: OrderItemDto[] } {
  const base = {
    groupCode: change.group.code,
    changeId: change.id,
    fromDiscipline: change.original.discipline?.shortName ?? null,
    fromTeacher: teacherText(change.original.teacher),
  };
  const room = (change.new.room ?? change.original.room)?.name ?? null;
  switch (change.kind) {
    case 'REPLACE_TEACHER':
    case 'REPLACE_DISCIPLINE':
      return {
        items: [
          row({
            ...base,
            key: change.id,
            pairLabel: pairLabelOf(change.pairFrom, change.pairTo),
            rowType: 'REPLACEMENT',
            reasonText: reasonLabel(change),
            toDiscipline: (change.new.discipline ?? change.original.discipline)?.shortName ?? null,
            toTeacher: teacherText(change.new.teacher),
            roomText: room,
          }),
        ],
        roomChanges: [],
      };
    case 'SWAP': {
      // пара, з якої переставили заняття на звільнений слот pairFrom (core: swapFromPair)
      const movedFrom = change.swapFromPair ?? change.pairFrom;
      return {
        items: [
          row({
            ...base,
            key: change.id,
            pairLabel: String(change.pairFrom),
            rowType: 'REPLACEMENT',
            reasonText: reasonLabel(change),
            toDiscipline: change.new.discipline?.shortName ?? null,
            toTeacher: teacherText(change.new.teacher),
            roomText: room,
          }),
          row({
            key: `${change.id}:moved`,
            groupCode: change.group.code,
            changeId: change.id,
            pairLabel: String(movedFrom),
            rowType: 'CANCELLATION',
            fromDiscipline: change.new.discipline?.shortName ?? null,
            fromTeacher: teacherText(change.new.teacher, change.subgroup > 0),
            reasonText: `На ${change.pairFrom} пару`,
          }),
        ],
        roomChanges: [],
      };
    }
    case 'CANCEL':
      return {
        items: [
          row({
            ...base,
            key: change.id,
            pairLabel: String(change.pairFrom),
            rowType: 'CANCELLATION',
            fromTeacher: teacherText(change.original.teacher, change.subgroup > 0),
            reasonText: reasonLabel(change),
          }),
        ],
        roomChanges: [],
      };
    case 'ADDITIONAL':
      return {
        items: [
          row({
            key: change.id,
            groupCode: change.group.code,
            changeId: change.id,
            pairLabel: String(change.pairFrom),
            rowType: 'ADDITIONAL',
            toDiscipline: change.new.discipline?.shortName ?? null,
            toTeacher: teacherText(change.new.teacher),
            roomText: room,
          }),
        ],
        roomChanges: [],
      };
    case 'BLOCK':
      return {
        items: [
          row({
            key: change.id,
            groupCode: change.group.code,
            changeId: change.id,
            pairLabel: pairLabelOf(change.pairFrom, change.pairTo),
            rowType: 'BLOCK',
            fromDiscipline: (change.new.discipline ?? change.original.discipline)?.shortName ?? null,
            fromTeacher: teacherText(change.new.teacher ?? change.original.teacher),
            roomText: room,
          }),
        ],
        roomChanges: [],
      };
    case 'DISCIPLINE_FINISHED':
      return {
        items: [
          row({
            ...base,
            key: change.id,
            pairLabel: pairLabelOf(change.pairFrom, change.pairTo),
            rowType: 'FINISHED',
            reasonText: REASON_CODE_LABEL.FINISHED,
            toDiscipline: change.new.discipline?.shortName ?? null,
            toTeacher: teacherText(change.new.teacher),
            roomText: change.new.discipline ? room : null,
          }),
        ],
        roomChanges: [],
      };
    case 'ROOM_CHANGE':
      return {
        items: [],
        roomChanges: [
          row({
            key: change.id,
            groupCode: change.group.code,
            changeId: change.id,
            pairLabel: String(change.pairFrom),
            rowType: 'ROOM_CHANGE',
            roomText: change.new.room?.name ?? null,
          }),
        ],
      };
  }
}

function firstPair(label: string): number {
  return Number.parseInt(label, 10) || 0;
}

/** Порядок рядків: курс, код групи, пара (DOMAIN §8). */
export function compareRows(a: OrderItemDto, b: OrderItemDto): number {
  return (
    firstPair(a.groupCode) - firstPair(b.groupCode) ||
    UK.compare(a.groupCode, b.groupCode) ||
    firstPair(a.pairLabel) - firstPair(b.pairLabel)
  );
}

export function itemsFromChanges(changes: readonly ChangeRecord[]): { items: OrderItemDto[]; roomChanges: OrderItemDto[] } {
  const items: OrderItemDto[] = [];
  const roomChanges: OrderItemDto[] = [];
  for (const change of changes) {
    const rows = rowsOf(change);
    items.push(...rows.items);
    roomChanges.push(...rows.roomChanges);
  }
  return { items: items.sort(compareRows), roomChanges: roomChanges.sort(compareRows) };
}

/** Примітки з календарних періодів, що діють на дату (практика, дистанційне навчання). */
export function calendarNotes(date: IsoDate): string[] {
  return CALENDAR_PERIODS.filter(
    (p) => p.orderNote && compareIsoDates(date, p.startsOn) >= 0 && compareIsoDates(date, p.endsOn) <= 0,
  ).map((p) => p.orderNote as string);
}

export function isWithinActiveSemester(date: IsoDate): boolean {
  return compareIsoDates(date, ACTIVE_SEMESTER.startsOn) >= 0 && compareIsoDates(date, ACTIVE_SEMESTER.endsOn) <= 0;
}

function publishedOn(world: World, date: IsoDate): OrderRecord[] {
  return [...world.orders.values()]
    .filter((o) => o.date === date && o.status === 'PUBLISHED')
    .sort((a, b) => (a.number ?? 0) - (b.number ?? 0));
}

/** Наступний номер: наскрізно в межах семестру (DOMAIN §8). */
export function nextOrderNumber(world: World, semesterId: Uuid): number {
  let max = 0;
  for (const order of world.orders.values()) {
    if (order.semesterId === semesterId && order.status === 'PUBLISHED' && order.number !== null) max = Math.max(max, order.number);
  }
  return max + 1;
}

/**
 * Чернетка на дату: усі підтверджені (ще не внесені) зміни дати + примітки. Якщо основне розпорядження
 * вже опубліковано — чернетка стає доповненням до нього. Примітки, які диспетчер редагував, зберігаються.
 */
export function buildDraft(world: World, date: IsoDate, settings: SettingsDto, now: Date): OrderRecord {
  const published = publishedOn(world, date);
  const main = published.find((o) => o.kind === 'ORDER') ?? null;
  const id = mockId('order-draft', `${date}#${published.length}`);
  const existing = world.orders.get(id);
  const confirmed = changesOn(world, date).filter((c) => c.status === 'CONFIRMED');
  const { items, roomChanges } = itemsFromChanges(confirmed);
  const draft: OrderRecord = {
    id,
    number: null,
    date,
    kind: main ? 'AMENDMENT' : 'ORDER',
    parentId: main?.id ?? null,
    status: 'DRAFT',
    title: orderTitle(main ? 'AMENDMENT' : 'ORDER', null, main?.number ?? null),
    weekdayLabel: weekdayLabel(date),
    parityLabel: PARITY_UPPER[parityOn(date)],
    items,
    roomChanges,
    // у доповненні календарні примітки вже були в основному розпорядженні
    notes: existing?.notes ?? (main ? [] : calendarNotes(date)),
    signatoryPosition: settings.signatoryPosition,
    signatoryName: settings.signatoryName,
    subtitle: ORDER_SUBTITLE,
    dateLabel: orderDateLabel(date),
    publishedAt: null,
    publishedBy: null,
    version: existing ? existing.version + 1 : 0,
    semesterId: ACTIVE_SEMESTER.id,
    createdAt: existing?.createdAt ?? now.toISOString(),
  };
  world.orders.set(id, draft);
  return draft;
}

export function toSummary(order: OrderRecord): OrderSummaryDto {
  return {
    id: order.id,
    number: order.number,
    date: order.date,
    kind: order.kind,
    status: order.status,
    itemCount: order.items.length + order.roomChanges.length,
    publishedAt: order.publishedAt,
  };
}

// ───────────────────────── Розпорядження №35 ─────────────────────────

export const ORDER_35_ID = mockId('order', 'published-35');

type Side = readonly [discipline: string, teacher: string] | null;

/** Рядок №35 точно як надруковано (документ обліку: тексти не нормалізуються). */
function printed(index: number, groupCode: string, pairLabel: string, rowType: OrderRowType, from: Side, reason: string | null, to: Side, room: string | null): OrderItemDto {
  return row({
    key: `35:${index}`,
    groupCode,
    pairLabel,
    rowType,
    fromDiscipline: from?.[0] ?? null,
    fromTeacher: from?.[1] ?? null,
    reasonText: reason,
    toDiscipline: to?.[0] ?? null,
    toTeacher: to?.[1] ?? null,
    roomText: room,
  });
}

/**
 * Розпорядження №35 на 19.10.2026 (ПОНЕДІЛОК — ЗНАМЕННИК) з фото seed/orders. Порядок рядків — як у
 * документі; останній рядок фото дублює рядок «1ОП2 4 БЖ Погосян 403» (описка), тут він один.
 */
export function order35(): OrderRecord {
  const items: OrderItemDto[] = [
    printed(1, '1ТК1', '1-4', 'BLOCK', ['ЗУ', 'Ємельяненко'], null, null, '204'),
    printed(2, '1ОП2', '4', 'ADDITIONAL', null, null, ['БЖ', 'Погосян'], '403'),
    printed(3, '1ТК1', '1', 'CANCELLATION', ['Ін. мова', 'Крисак Л. (п.)'], 'Відп.', null, null),
    printed(4, '1КІ2', '2', 'REPLACEMENT', ['Ін. мова', 'Крисак Л.'], 'Відп.', ['Ін.мова', 'Прокопчук'], '110'),
    printed(5, '1М', '3', 'REPLACEMENT', ['Ін. мова', 'Крисак Л.'], 'Відп.', ['Ін.мова', 'Прокопчук'], '110'),
    printed(6, '1ЕА2', '3', 'REPLACEMENT', ['ЕТ', 'Масловата'], 'Зв.зам.', ['Культ.', 'Шароварська'], '405'),
    printed(7, '2ОД', '4', 'ADDITIONAL', null, null, ['ІКТ', 'Вернигора'], '309'),
    printed(8, '2ОД', '6', 'REPLACEMENT', ['ІМД', 'Масловата'], 'Зв.зам.', ['ОД', 'Яцуба'], '308'),
    printed(9, '2ОБ', '7', 'REPLACEMENT', ['ЕіФБ', 'Охота О.'], 'Зв.зам.', ['ІКТ', 'Вернигора'], '304'),
    printed(10, '2ОБ', '8', 'REPLACEMENT', ['ІМД', 'Масловата'], 'Зв.зам.', ['ТБО', 'Фабіянська'], '409'),
    printed(11, '2М', '8', 'REPLACEMENT', ['ЕіФБ', 'Охота О.'], 'Зв.зам.', ['Укр.літ.', 'Кучинська'], '303'),
    printed(12, '2М', '5', 'REPLACEMENT', ['ІМД', 'Масловата'], 'Зв.зам.', ['Вс.іст.', 'Балунь'], '409'),
    printed(13, '2Ф', '7', 'REPLACEMENT', ['ІМД', 'Масловата'], 'Зв.зам.', ['ЕіФБ', 'Кукурудзяк'], '408'),
    printed(14, '3Ф', '4', 'CANCELLATION', ['Ін.мова', 'Крисак Л. (п.)'], 'Відп.', null, null),
    printed(15, '4КІ1', '2', 'REPLACEMENT', ['СТП', 'Кренцін'], 'Зв.зам.', ['НДКС', 'Непийвода'], '6'),
    printed(16, '4КІ2', '4', 'REPLACEMENT', ['СТП', 'Кренцін'], 'Зв.зам.', ['ОПЦЗ', 'Ганза'], '6'),
  ];
  return {
    id: ORDER_35_ID,
    number: 35,
    date: '2026-10-19',
    kind: 'ORDER',
    parentId: null,
    status: 'PUBLISHED',
    title: orderTitle('ORDER', 35, null),
    weekdayLabel: 'ПОНЕДІЛОК',
    parityLabel: 'ЗНАМЕННИК',
    items,
    roomChanges: [],
    notes: [
      '2КІ3 – Електрорадіомонтажна практика з 19.10.26р. по 30.10.26 р.',
      '4ОП2 - Основи національного спротиву з 05.10.26р. по 16.10.26р. 401- аудиторія.',
      '3 курс групи 3Ф,3ОД, 3ЕП, 3ОБ, 3РТ,3ТК,3ЕА,3ВЕ,3ОП з 1.09.26р. по 31.10.26.– Навчаються за розкладом дистанційно.',
      'Групи 4КІ з 01.09.26р. по 31.10.26 навчаються – дистанційно.',
      '4ЕА,4ВЕ,4РТ,4ОП навчаються очно з 01.09.26 по 31.10.26 р.',
    ],
    signatoryPosition: 'Заступник директора з НР',
    signatoryName: 'Микола ПОЛІЩУК',
    subtitle: ORDER_SUBTITLE,
    dateLabel: orderDateLabel('2026-10-19'),
    // ДЕМО: час публікації на фото не видно — пʼятниця перед датою розпорядження
    publishedAt: '2026-10-16T12:40:00.000Z',
    publishedBy: 'admin',
    version: 1,
    semesterId: ACTIVE_SEMESTER.id,
    createdAt: '2026-10-16T11:05:00.000Z',
  };
}
