/**
 * Публічний мок-API (CONTRACTS §1): довідники, дзвінки, розклади зі змінами, заміни на дату, ICS, запрошення.
 *
 * Зміни розкладу (заміни, скасування, аудиторії) — ДЕМО, побудовані з довідників і шаблону:
 * - 19.10.2026 — справжні рядки розпорядження №35 (DOMAIN §11), крім блоку «ЗУ Ємельяненко»:
 *   такої дисципліни й викладача в таблиці розкладу немає, а вигадувати їх не можна;
 * - «сьогодні» і два наступні навчальні дні — детерміновані зміни, підібрані так, щоб не було конфліктів
 *   (замінює вільний викладач тієї ж дисципліни, аудиторія вільна, додаткова пара — у вікні зміни групи).
 * Зміни стають публічними, коли «опубліковано» розпорядження: о 14:20 попереднього навчального дня
 * (DOMAIN §7: заняття змінюються саме під час публікації). Запити до Кренціна (демо-обліковий запис) чекають
 * на його відповідь у кабінеті — їх віддає handlers/me.ts.
 */
import { http, HttpResponse } from 'msw';
import type {
  AcceptInviteRequest,
  BellsDto,
  ChangeDto,
  ChangeParties,
  ChangeStatus,
  ChangeView,
  EntityType,
  GroupDto,
  InviteInfoDto,
  IsoDate,
  IsoInstant,
  LessonView,
  OrderRowType,
  PublicChangesDto,
  ReasonCode,
  ScheduleOwner,
  TeacherRef,
  Uuid,
  Weekday,
} from '@/api/types';
import { addDays, compareIsoDates, isIsoDate, isoWeekday, kyivClock, timeToMinutes, todayInKyiv } from '@/lib/date';
import {
  ACTIVE_BELL_PROFILE,
  ACTIVE_SEMESTER,
  BELL_PROFILES,
  bellSlots,
  buildSchedule,
  disciplineNamed,
  disciplineRef,
  GROUPS,
  groupNamed,
  isStudyDay,
  lessonsOn,
  parityOn,
  ROOMS,
  roomNamed,
  roomRef,
  scheduleOwner,
  scheduleRangeError,
  SHIFT_WINDOWS,
  TEACHERS,
  teacherNamed,
  teacherRef,
  TEMPLATE_LESSONS,
  toPublicGroup,
  toPublicRoom,
  toPublicTeacher,
  weekdayLabel,
} from '../fixtures';
import { mockId } from '../ids';
import { apiPath, badRequest, notFound, problem, readJson } from '../kit';
import { createMockStore } from '../store';
import { printedReason } from './admin-daily/printed';

const UK = new Intl.Collator('uk');

// ───────────────────────── Час у Києві ─────────────────────────

/** ISO-instant для київського «дата + час» (зимовий час UTC+2, літній UTC+3). */
export function kyivInstant(date: IsoDate, time: string): IsoInstant {
  const [year, month, day] = date.split('-').map(Number) as [number, number, number];
  const minutes = timeToMinutes(time);
  for (const offsetHours of [2, 3]) {
    const candidate = new Date(Date.UTC(year, month - 1, day, 0, minutes - offsetHours * 60));
    const clock = kyivClock(candidate);
    if (clock.date === date && clock.minutes === minutes) return candidate.toISOString();
  }
  throw new Error(`Мок-дані: неможливий час ${date} ${time} у Києві`);
}

// ───────────────────────── Навчальні дні й розпорядження ─────────────────────────

/** Наступний навчальний день семестру (з урахуванням канікул) або null, якщо семестр скінчився. */
function nextStudyDay(date: IsoDate, direction: 1 | -1 = 1): IsoDate | null {
  let cursor = addDays(date, direction);
  for (let step = 0; step < 21; step += 1) {
    if (isStudyDay(cursor)) return cursor;
    cursor = addDays(cursor, direction);
  }
  return null;
}

/** Найближчий навчальний день, починаючи з дати. */
function studyDayFrom(date: IsoDate): IsoDate | null {
  return isStudyDay(date) ? date : nextStudyDay(date);
}

/**
 * Номер розпорядження = порядковий номер навчального дня в семестрі: розпорядження виходить щодня,
 * №35 припало на 19.10.2026 — 35-й навчальний день від 01.09 (DOMAIN §8).
 */
export function orderNumberFor(date: IsoDate): number {
  let count = 0;
  for (let cursor = ACTIVE_SEMESTER.startsOn; compareIsoDates(cursor, date) <= 0; cursor = addDays(cursor, 1)) {
    if (isStudyDay(cursor)) count += 1;
  }
  return count;
}

/** Демо-правило публікації: розпорядження на день виходить о 14:20 попереднього навчального дня. */
export function publishedAtFor(date: IsoDate): IsoInstant {
  return kyivInstant(nextStudyDay(date, -1) ?? addDays(date, -1), '14:20');
}

export function isPublished(date: IsoDate, now: Date): boolean {
  return now.getTime() >= new Date(publishedAtFor(date)).getTime();
}

export const orderIdFor = (date: IsoDate): Uuid => mockId('order', date);

// ───────────────────────── Демо-зміни ─────────────────────────

/** Викладач демо-облікового запису `krentsin` (DOMAIN §12). */
export const KRENTSIN = teacherRef(teacherNamed('Кренцін'));

/** Дата розпорядження №35 — єдиного, рядки якого відомі напевно. */
export const ORDER_35_DATE = '2026-10-19';

/** Хто веде яку дисципліну за шаблоном (компетенція для підбору заміни, DOMAIN §7). */
const TEACHERS_BY_DISCIPLINE: ReadonlyMap<Uuid, readonly TeacherRef[]> = (() => {
  const map = new Map<Uuid, Map<Uuid, TeacherRef>>();
  for (const lesson of TEMPLATE_LESSONS) {
    if (!lesson.teacher) continue;
    const teachers = map.get(lesson.discipline.id) ?? new Map<Uuid, TeacherRef>();
    teachers.set(lesson.teacher.id, lesson.teacher);
    map.set(lesson.discipline.id, teachers);
  }
  return new Map(
    [...map].map(([id, teachers]) => [id, [...teachers.values()].sort((a, b) => UK.compare(a.shortName, b.shortName))]),
  );
})();

interface ChangeSeed {
  date: IsoDate;
  key: string;
  kind: ChangeDto['kind'];
  status: ChangeStatus;
  /** Заняття, яке змінюється; для додаткової пари — null. */
  lesson: LessonView | null;
  group: LessonView['group'];
  subgroup: number;
  pair: number;
  next: Partial<ChangeParties>;
  reasonCode: ReasonCode;
  reasonText?: string | null;
  confirmDeadline?: IsoInstant | null;
}

function createChange(seed: ChangeSeed): ChangeDto {
  const createdAt = kyivInstant(nextStudyDay(seed.date, -1) ?? addDays(seed.date, -1), '12:05');
  const target = seed.status === 'PENDING_CONFIRMATION' ? 'PENDING_CONFIRMATION' : 'CONFIRMED';
  return {
    id: mockId('change', `${seed.date}|${seed.key}`),
    kind: seed.kind,
    status: seed.status,
    date: seed.date,
    pairFrom: seed.pair,
    pairTo: seed.pair,
    group: seed.group,
    subgroup: seed.subgroup,
    original: {
      discipline: seed.lesson?.discipline ?? null,
      teacher: seed.lesson?.teacher ?? null,
      room: seed.lesson?.room ?? null,
    },
    new: { discipline: seed.next.discipline ?? null, teacher: seed.next.teacher ?? null, room: seed.next.room ?? null },
    reasonCode: seed.reasonCode,
    reasonText: seed.reasonText ?? null,
    absenceId: null,
    confirmDeadline: seed.confirmDeadline ?? null,
    respondedAt: null,
    orderId: null,
    history: [
      { at: createdAt, actor: 'admin', from: null, to: 'DRAFT', comment: null },
      { at: createdAt, actor: 'admin', from: 'DRAFT', to: target, comment: null },
    ],
    approvalMode: 'CONFIRM',
    originalLessonId: seed.lesson?.id ?? null,
    swapLessonId: null,
    swapFromPair: null,
    printedReason: printedReason(seed.kind, seed.reasonCode, seed.reasonText ?? null),
    responseComment: null,
    remindedAt: null,
    createdBy: 'admin',
    createdAt,
    version: 1,
    supersededBy: null,
  };
}

/** Рядки розпорядження №35 на 19.10.2026 (ПОНЕДІЛОК — ЗНАМЕННИК), DOMAIN §11. */
function order35Changes(): ChangeDto[] {
  const date = ORDER_35_DATE;
  const lessons = lessonsOn(date);
  const find = (code: string, pair: number, subgroup: number): LessonView => {
    const lesson = lessons.find((l) => l.group.code === code && l.pair === pair && l.subgroup === subgroup);
    if (!lesson) throw new Error(`Мок-дані неузгоджені: у ${code} немає ${pair} пари (${subgroup} п/г) ${date}`);
    return lesson;
  };
  const seed = (key: string, lesson: LessonView, rest: Pick<ChangeSeed, 'kind' | 'next' | 'reasonCode'>): ChangeDto =>
    createChange({ date, key, status: 'CONFIRMED', lesson, group: lesson.group, subgroup: lesson.subgroup, pair: lesson.pair, ...rest });
  return [
    // 1КІ2 | 2 | Ін. мова Крисак Л. | Відп. | Ін.мова Прокопчук | 110
    seed('1КІ2|2', find('1КІ2', 2, 1), {
      kind: 'REPLACE_TEACHER',
      next: { teacher: teacherRef(teacherNamed('Прокопчук')) },
      reasonCode: 'ABSENT',
    }),
    // 4КІ1 | 2 | СТП Кренцін | Зв.зам. | НДКС Непийвода | 6
    seed('4КІ1|2', find('4КІ1', 2, 0), {
      kind: 'REPLACE_DISCIPLINE',
      next: {
        discipline: disciplineRef(disciplineNamed('НДЕКСМ')),
        teacher: teacherRef(teacherNamed('Непийвода')),
        room: roomRef(roomNamed('6')),
      },
      reasonCode: 'MUTUAL',
    }),
    // 1ТК1 | 1 | Ін. мова Крисак Л. (п.) | Відп. | Пари не буде
    seed('1ТК1|1', find('1ТК1', 1, 1), { kind: 'CANCEL', next: {}, reasonCode: 'ABSENT' }),
  ];
}

type DemoRole = 'anchor' | 'next' | 'after';

/** Підбір демо-змін на день без конфліктів: зайняті викладачі й аудиторії, використані заняття. */
class DayPlanner {
  private readonly used = new Set<Uuid>();
  private readonly extraBusy = new Set<string>();
  private readonly date: IsoDate;
  private readonly lessons: readonly LessonView[];

  constructor(date: IsoDate, lessons: readonly LessonView[]) {
    this.date = date;
    this.lessons = lessons;
  }

  private teacherBusy(teacherId: Uuid, pair: number): boolean {
    return (
      this.extraBusy.has(`t:${teacherId}:${pair}`) || this.lessons.some((l) => l.pair === pair && l.teacher?.id === teacherId)
    );
  }

  private roomBusy(roomId: Uuid, pair: number): boolean {
    return this.extraBusy.has(`r:${roomId}:${pair}`) || this.lessons.some((l) => l.pair === pair && l.room?.id === roomId);
  }

  private groupLessons(code: string): LessonView[] {
    return this.lessons.filter((l) => l.group.code === code && !this.used.has(l.id));
  }

  private take(lesson: LessonView, next: Partial<ChangeParties>): void {
    this.used.add(lesson.id);
    if (next.teacher) this.extraBusy.add(`t:${next.teacher.id}:${lesson.pair}`);
    if (next.room) this.extraBusy.add(`r:${next.room.id}:${lesson.pair}`);
  }

  private seed(key: string, lesson: LessonView, rest: Omit<ChangeSeed, 'date' | 'key' | 'lesson' | 'group' | 'subgroup' | 'pair'>) {
    this.take(lesson, rest.next);
    return createChange({ date: this.date, key, lesson, group: lesson.group, subgroup: lesson.subgroup, pair: lesson.pair, ...rest });
  }

  /** Викладач відсутній — його пару бере вільний колега, що веде ту саму дисципліну («Відп.»). */
  replaceTeacher(codes: readonly string[], key: string): ChangeDto | null {
    for (const code of codes) {
      for (const lesson of this.groupLessons(code)) {
        const absent = lesson.teacher;
        if (!absent || absent.id === KRENTSIN.id) continue;
        const substitute = (TEACHERS_BY_DISCIPLINE.get(lesson.discipline.id) ?? []).find(
          (t) => t.id !== absent.id && t.id !== KRENTSIN.id && !this.teacherBusy(t.id, lesson.pair),
        );
        if (substitute) {
          return this.seed(key, lesson, { kind: 'REPLACE_TEACHER', status: 'CONFIRMED', next: { teacher: substitute }, reasonCode: 'ABSENT' });
        }
      }
    }
    return null;
  }

  /** Остання пара групи скасована («Пари не буде», «Відп.») — група піде раніше. */
  cancelLast(codes: readonly string[], key: string): ChangeDto | null {
    for (const code of codes) {
      const lesson = this.groupLessons(code)
        .filter((l) => l.subgroup === 0)
        .sort((a, b) => b.pair - a.pair)[0];
      if (lesson) return this.seed(key, lesson, { kind: 'CANCEL', status: 'CONFIRMED', next: {}, reasonCode: 'ABSENT' });
    }
    return null;
  }

  /** Пара переїжджає до найменшої за номером вільної аудиторії (розділ «Заміна аудиторій»). */
  changeRoom(codes: readonly string[], key: string): ChangeDto | null {
    const classrooms = ROOMS.filter((r) => r.type === 'CLASSROOM' && /^\d{3}$/.test(r.name));
    for (const code of codes) {
      for (const lesson of this.groupLessons(code)) {
        if (!lesson.room || !/^\d+$/.test(lesson.room.name)) continue;
        const target = classrooms.find((r) => r.id !== lesson.room?.id && !this.roomBusy(r.id, lesson.pair));
        if (target) {
          return this.seed(key, lesson, { kind: 'ROOM_CHANGE', status: 'CONFIRMED', next: { room: roomRef(target) }, reasonCode: 'OTHER' });
        }
      }
    }
    return null;
  }

  /** Додаткова пара (відпрацювання) одразу після останньої — лише у вікні зміни групи й за вільних викладача та аудиторії. */
  additional(codes: readonly string[], key: string): ChangeDto | null {
    const weekday = isoWeekday(this.date) as Weekday;
    for (const code of codes) {
      const group: GroupDto = groupNamed(code);
      const window = SHIFT_WINDOWS.find((w) => w.shift === group.shift && w.weekday === weekday);
      const own = this.groupLessons(code);
      if (!window || own.length === 0) continue;
      const pair = Math.max(...own.map((l) => l.pair)) + 1;
      if (pair > window.lastPair || !bellSlots(weekday).some((slot) => slot.pair === pair)) continue;
      const source = own.find((l) => l.teacher && l.teacher.id !== KRENTSIN.id && !this.teacherBusy(l.teacher.id, pair));
      const room = source?.room && !this.roomBusy(source.room.id, pair) ? source.room : (group.homeRoom ?? null);
      if (!source || !room || this.roomBusy(room.id, pair)) continue;
      const next: ChangeParties = { discipline: source.discipline, teacher: source.teacher ?? null, room };
      if (next.teacher) this.extraBusy.add(`t:${next.teacher.id}:${pair}`);
      this.extraBusy.add(`r:${room.id}:${pair}`);
      return createChange({
        date: this.date,
        key,
        kind: 'ADDITIONAL',
        status: 'CONFIRMED',
        lesson: null,
        group: source.group,
        subgroup: 0,
        pair,
        next,
        reasonCode: 'OTHER',
      });
    }
    return null;
  }

  /** Запит до Кренціна: замінити відсутнього колегу в пару, коли він вільний. */
  krentsinReplace(codes: readonly string[], key: string, status: ChangeStatus): ChangeDto | null {
    for (const code of codes) {
      for (const lesson of this.groupLessons(code)) {
        if (lesson.subgroup !== 0 || !lesson.teacher || lesson.teacher.id === KRENTSIN.id) continue;
        if (this.teacherBusy(KRENTSIN.id, lesson.pair)) continue;
        return this.seed(key, lesson, {
          kind: 'REPLACE_TEACHER',
          status,
          next: { teacher: KRENTSIN },
          reasonCode: 'ABSENT',
          confirmDeadline: status === 'PENDING_CONFIRMATION' ? kyivInstant(this.date, '07:30') : null,
        });
      }
    }
    return null;
  }

  /** Взаємна заміна: замість чужої пари Кренцін проводить свою СТП («Зв.зам.»). */
  krentsinSwap(codes: readonly string[], key: string): ChangeDto | null {
    const stp = disciplineRef(disciplineNamed('СТП'));
    for (const code of codes) {
      for (const lesson of this.groupLessons(code)) {
        if (lesson.subgroup !== 0 || lesson.teacher?.id === KRENTSIN.id || lesson.discipline.id === stp.id) continue;
        if (this.teacherBusy(KRENTSIN.id, lesson.pair)) continue;
        return this.seed(key, lesson, {
          kind: 'REPLACE_DISCIPLINE',
          status: 'PENDING_CONFIRMATION',
          next: { discipline: stp, teacher: KRENTSIN },
          reasonCode: 'MUTUAL',
          confirmDeadline: kyivInstant(this.date, '07:30'),
        });
      }
    }
    return null;
  }
}

const KI_GROUPS = ['4КІ2', '4КІ3', '3КІ1', '3КІ2', '3КІ3', '4КІ1'] as const;

function generatedChanges(date: IsoDate, role: DemoRole): ChangeDto[] {
  const planner = new DayPlanner(date, lessonsOn(date));
  const changes =
    role === 'anchor'
      ? [
          planner.replaceTeacher(['4КІ1', '3КІ1', '1КІ1'], 'absent'),
          planner.cancelLast(['1КІ2', '1КІ1', '1ТК1'], 'cancel'),
          planner.changeRoom(['2КІ1', '2КІ2', '2РТ1'], 'room'),
          planner.krentsinReplace(KI_GROUPS, 'krentsin', 'CONFIRMED'),
        ]
      : role === 'next'
        ? [
            planner.replaceTeacher(['3КІ1', '4КІ1', '2КІ3'], 'absent'),
            planner.additional(['4КІ1', '4КІ2', '4КІ3'], 'extra'),
            planner.krentsinReplace(KI_GROUPS, 'krentsin', 'PENDING_CONFIRMATION'),
          ]
        : [planner.krentsinSwap(KI_GROUPS, 'krentsin-swap'), planner.cancelLast(['2ОБ', '2Ф', '2М'], 'cancel')];
  return changes.filter((change): change is ChangeDto => change !== null);
}

/** Дати з демо-змінами відносно «зараз»: сьогодні (або найближчий навчальний день) і два наступні. */
export function demoDates(now: Date): Array<{ date: IsoDate; role: DemoRole }> {
  const anchor = studyDayFrom(todayInKyiv(now));
  if (!anchor) return [];
  const next = nextStudyDay(anchor);
  const after = next ? nextStudyDay(next) : null;
  const dates: Array<{ date: IsoDate; role: DemoRole }> = [{ date: anchor, role: 'anchor' }];
  if (next) dates.push({ date: next, role: 'next' });
  if (after) dates.push({ date: after, role: 'after' });
  // для дати з відомим розпорядженням — лише його рядки
  return dates.filter((entry) => entry.date !== ORDER_35_DATE);
}

/** Генерація залежить лише від дати й ролі — кешуємо, щоб не перебирати шаблон на кожен запит. */
const generatedCache = new Map<string, ChangeDto[]>();

function baseChangesFor(date: IsoDate, now: Date): ChangeDto[] {
  if (date === ORDER_35_DATE) return cached(`${date}|order35`, order35Changes);
  const entry = demoDates(now).find((d) => d.date === date);
  return entry ? cached(`${date}|${entry.role}`, () => generatedChanges(date, entry.role)) : [];
}

function cached(key: string, build: () => ChangeDto[]): ChangeDto[] {
  let value = generatedCache.get(key);
  if (!value) {
    value = build();
    generatedCache.set(key, value);
  }
  return value;
}

/** Відповіді викладача на запити (стан мок-API; скидається між тестами). */
interface ReplyRecord {
  status: Extract<ChangeStatus, 'CONFIRMED' | 'DECLINED'>;
  at: IsoInstant;
  comment: string | null;
  actor: string;
}
const replies = createMockStore<Map<Uuid, ReplyRecord>>(() => new Map());

/** Зміна з урахуванням відповіді викладача і публікації розпорядження. */
function effective(change: ChangeDto, now: Date): ChangeDto {
  const reply = replies.get().get(change.id);
  let result = change;
  if (reply) {
    result = {
      ...result,
      status: reply.status,
      respondedAt: reply.at,
      history: [...result.history, { at: reply.at, actor: reply.actor, from: 'PENDING_CONFIRMATION', to: reply.status, comment: reply.comment }],
    };
  }
  if (result.status === 'CONFIRMED' && isPublished(result.date, now)) {
    result = {
      ...result,
      status: 'APPLIED',
      orderId: orderIdFor(result.date),
      history: [...result.history, { at: publishedAtFor(result.date), actor: 'admin', from: 'CONFIRMED', to: 'APPLIED', comment: null }],
    };
  }
  return result;
}

export function changesOn(date: IsoDate, now: Date): ChangeDto[] {
  return baseChangesFor(date, now).map((change) => effective(change, now));
}

/** Усі демо-зміни, де новий викладач — `teacherId` (GET /api/me/changes). */
export function changesForTeacher(teacherId: Uuid, now: Date): ChangeDto[] {
  const dates = [ORDER_35_DATE, ...demoDates(now).map((d) => d.date)];
  return dates
    .flatMap((date) => changesOn(date, now))
    .filter((change) => change.new.teacher?.id === teacherId)
    .sort((a, b) => compareIsoDates(a.date, b.date) || a.pairFrom - b.pairFrom);
}

/** Відповідь викладача на запит: 404 — не його, 409 — уже не чекає відповіді. Повертає оновлену зміну. */
export function replyToChange(
  id: Uuid,
  teacherId: Uuid,
  decision: 'CONFIRM' | 'DECLINE',
  comment: string | null,
  actor: string,
  now: Date,
): ChangeDto {
  const change = changesForTeacher(teacherId, now).find((c) => c.id === id);
  if (!change) throw notFound(`Запит на заміну з id ${id} не знайдено серед ваших.`);
  if (change.status !== 'PENDING_CONFIRMATION') {
    throw problem({ status: 409, detail: 'На цей запит уже відповіли — оновіть сторінку.' });
  }
  replies.get().set(id, { status: decision === 'CONFIRM' ? 'CONFIRMED' : 'DECLINED', at: now.toISOString(), comment, actor });
  return effective(change, now);
}

// ───────────────────────── Зміни → розклад ─────────────────────────

function isVisible(change: ChangeDto): boolean {
  return change.status === 'APPLIED';
}

function changedLesson(lesson: LessonView, change: ChangeDto): LessonView {
  // як core: у розкладі — та сама надрукована причина, що й у розпорядженні (ReasonCode.printed)
  const reasonText = change.printedReason;
  const discipline = change.new.discipline ?? lesson.discipline;
  const room = change.new.room ?? lesson.room ?? null;
  switch (change.kind) {
    case 'CANCEL':
    case 'DISCIPLINE_FINISHED':
      return { ...lesson, kind: 'CANCELLED', reasonText, changeId: change.id };
    case 'ROOM_CHANGE':
      return {
        ...lesson,
        kind: 'ROOM_CHANGED',
        room: change.new.room ?? lesson.room,
        original: { discipline: null, teacher: null, room: lesson.room ?? null, pair: null },
        reasonText: null,
        changeId: change.id,
      };
    case 'BLOCK':
      return { ...lesson, kind: 'BLOCK', discipline, teacher: change.new.teacher ?? lesson.teacher ?? null, room, changeId: change.id };
    case 'ADDITIONAL':
      // додаткова пара не замінює наявну — її додає additionalLesson
      return lesson;
    case 'REPLACE_TEACHER':
    case 'REPLACE_DISCIPLINE':
    case 'SWAP':
      return {
        ...lesson,
        id: mockId('lesson', `change:${change.id}`),
        kind: 'SUBSTITUTED',
        discipline,
        teacher: change.new.teacher ?? lesson.teacher ?? null,
        room,
        original: {
          discipline: discipline.id !== lesson.discipline.id ? lesson.discipline : null,
          teacher: lesson.teacher ?? null,
          room: room?.id !== lesson.room?.id ? (lesson.room ?? null) : null,
          pair: null,
        },
        reasonText,
        changeId: change.id,
      };
  }
}

function additionalLesson(date: IsoDate, change: ChangeDto): LessonView | null {
  const bell = bellSlots(isoWeekday(date) as Weekday).find((slot) => slot.pair === change.pairFrom);
  if (!bell || !change.new.discipline) return null;
  return {
    id: mockId('lesson', `change:${change.id}`),
    pair: change.pairFrom,
    startsAt: bell.startsAt,
    endsAt: bell.endsAt,
    discipline: change.new.discipline,
    teacher: change.new.teacher ?? null,
    room: change.new.room ?? null,
    group: change.group,
    subgroup: change.subgroup,
    kind: 'ADDITIONAL',
    online: false,
    original: null,
    reasonText: null,
    changeId: change.id,
  };
}

/** Накладає опубліковані зміни дати на заняття всіх груп (ScheduleOptions.overlay). */
export function applyChanges(date: IsoDate, lessons: LessonView[], changes: readonly ChangeDto[]): LessonView[] {
  const result = [...lessons];
  for (const change of changes) {
    if (!isVisible(change)) continue;
    if (change.kind === 'ADDITIONAL') {
      const extra = additionalLesson(date, change);
      if (extra) result.push(extra);
      continue;
    }
    const index = result.findIndex(
      (l) => l.kind === 'REGULAR' && l.group.id === change.group.id && l.subgroup === change.subgroup && l.pair === change.pairFrom,
    );
    // шаблон змінився і пари вже немає — зміна нічого не зачіпає
    if (index >= 0) result[index] = changedLesson(result[index] as LessonView, change);
  }
  return result;
}

export function publicOverlay(now: Date): (date: IsoDate, lessons: LessonView[]) => LessonView[] {
  return (date, lessons) => applyChanges(date, lessons, changesOn(date, now));
}

// ───────────────────────── Зміни → рядки «Заміни» ─────────────────────────

const ROW_TYPE: Record<ChangeDto['kind'], OrderRowType> = {
  REPLACE_TEACHER: 'REPLACEMENT',
  REPLACE_DISCIPLINE: 'REPLACEMENT',
  SWAP: 'REPLACEMENT',
  CANCEL: 'CANCELLATION',
  ADDITIONAL: 'ADDITIONAL',
  ROOM_CHANGE: 'ROOM_CHANGE',
  BLOCK: 'BLOCK',
  DISCIPLINE_FINISHED: 'FINISHED',
};

/** Рядок у форматі розпорядження: прізвища з ініціалом («Крисак Л.»), короткі назви дисциплін. */
export function toChangeView(change: ChangeDto): ChangeView {
  const rowType = ROW_TYPE[change.kind];
  const before = { discipline: change.original.discipline?.shortName ?? null, teacher: change.original.teacher?.displayName ?? null };
  const after = {
    discipline: (change.new.discipline ?? change.original.discipline)?.shortName ?? null,
    teacher: (change.new.teacher ?? change.original.teacher)?.displayName ?? null,
  };
  return {
    pairLabel: change.pairTo && change.pairTo !== change.pairFrom ? `${change.pairFrom}-${change.pairTo}` : String(change.pairFrom),
    rowType,
    from: rowType === 'ADDITIONAL' ? { discipline: null, teacher: null } : before,
    reasonText: change.printedReason,
    to: rowType === 'CANCELLATION' || rowType === 'ROOM_CHANGE' ? { discipline: null, teacher: null } : after,
    room: rowType === 'CANCELLATION' ? null : ((change.new.room ?? change.original.room)?.name ?? null),
    subgroupNote: change.subgroup > 0 ? `${change.subgroup} підгрупа` : null,
    orderNumber: null,
  };
}

export function publicChanges(date: IsoDate, now: Date): PublicChangesDto {
  const visible = changesOn(date, now).filter(isVisible);
  const byGroup = new Map<Uuid, ChangeDto[]>();
  for (const change of visible) byGroup.set(change.group.id, [...(byGroup.get(change.group.id) ?? []), change]);
  const groups = [...byGroup.values()]
    .map((changes) => changes.sort((a, b) => a.pairFrom - b.pairFrom || a.subgroup - b.subgroup))
    .sort((a, b) => {
      const ga = (a[0] as ChangeDto).group;
      const gb = (b[0] as ChangeDto).group;
      return ga.course - gb.course || UK.compare(ga.code, gb.code);
    })
    .map((changes) => ({
      group: (changes[0] as ChangeDto).group,
      // демо-дні мають лише основне розпорядження: кожен рядок надруковано в ньому
      items: changes.map((change) => ({ ...toChangeView(change), orderNumber: orderNumberFor(date) })),
    }));
  return {
    date,
    parity: parityOn(date),
    weekdayLabel: weekdayLabel(date),
    order:
      visible.length > 0 ? { id: orderIdFor(date), number: orderNumberFor(date), publishedAt: publishedAtFor(date), imported: false } : null,
    amendments: [],
    groups,
  };
}

// ───────────────────────── ICS ─────────────────────────

/** RFC 5545: екрануємо \ ; , і переноси рядків. */
function icsText(value: string): string {
  return value.replace(/\\/g, '\\\\').replace(/;/g, '\\;').replace(/,/g, '\\,').replace(/\r?\n/g, '\\n');
}

/** Рядки довші за 75 байтів UTF-8 переносимо, не розриваючи багатобайтові символи. */
function foldLine(line: string): string {
  const encoder = new TextEncoder();
  const parts: string[] = [];
  let current = '';
  let bytes = 0;
  for (const char of line) {
    const size = encoder.encode(char).length;
    if (bytes + size > (parts.length === 0 ? 75 : 74)) {
      parts.push(current);
      current = '';
      bytes = 0;
    }
    current += char;
    bytes += size;
  }
  parts.push(current);
  return parts.join('\r\n ');
}

const stamp = (date: Date) => date.toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');
const localStamp = (date: IsoDate, time: string) => `${date.replace(/-/g, '')}T${time.replace(':', '')}00`;

const VTIMEZONE_KYIV = [
  'BEGIN:VTIMEZONE',
  'TZID:Europe/Kyiv',
  'BEGIN:STANDARD',
  'DTSTART:19701025T040000',
  'TZOFFSETFROM:+0300',
  'TZOFFSETTO:+0200',
  'TZNAME:EET',
  'RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU',
  'END:STANDARD',
  'BEGIN:DAYLIGHT',
  'DTSTART:19700329T030000',
  'TZOFFSETFROM:+0200',
  'TZOFFSETTO:+0300',
  'TZNAME:EEST',
  'RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU',
  'END:DAYLIGHT',
  'END:VTIMEZONE',
];

/** Календар власника на семестр: опубліковані заняття, скасовані пропускаємо. */
export function icsCalendar(owner: ScheduleOwner, now: Date): string {
  const schedule = buildSchedule(owner.type, owner.id, ACTIVE_SEMESTER.startsOn, ACTIVE_SEMESTER.endsOn, {
    overlay: publicOverlay(now),
  });
  const title = owner.type === 'GROUP' ? `Розклад ${owner.label}` : `Розклад: ${owner.label}`;
  const lines = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//Розклад ВТФК//Розклад занять//UK',
    'CALSCALE:GREGORIAN',
    'METHOD:PUBLISH',
    `X-WR-CALNAME:${icsText(title)}`,
    'X-WR-TIMEZONE:Europe/Kyiv',
    ...VTIMEZONE_KYIV,
  ];
  for (const day of schedule?.days ?? []) {
    for (const lesson of day.lessons) {
      if (lesson.kind === 'CANCELLED') continue;
      const who = owner.type === 'GROUP' ? lesson.teacher?.displayName : lesson.group.code;
      const summary = [lesson.discipline.shortName, who].filter(Boolean).join(' · ');
      const notes = [
        lesson.discipline.name,
        lesson.subgroup > 0 ? `${lesson.subgroup} підгрупа` : null,
        lesson.kind === 'SUBSTITUTED' ? `Заміна${lesson.reasonText ? ` (${lesson.reasonText})` : ''}` : null,
        lesson.kind === 'ADDITIONAL' ? 'Додаткова пара' : null,
        lesson.online ? 'Дистанційно' : null,
      ].filter(Boolean);
      // без профілю дзвінків час пари невідомий — у календар таку пару не кладемо
      if (!lesson.startsAt || !lesson.endsAt) continue;
      lines.push(
        'BEGIN:VEVENT',
        `UID:${lesson.id}@rozklad.vtfk`,
        `DTSTAMP:${stamp(now)}`,
        `DTSTART;TZID=Europe/Kyiv:${localStamp(day.date, lesson.startsAt)}`,
        `DTEND;TZID=Europe/Kyiv:${localStamp(day.date, lesson.endsAt)}`,
        `SUMMARY:${icsText(`${lesson.pair} пара: ${summary}`)}`,
        ...(lesson.room ? [`LOCATION:${icsText(/^\d/.test(lesson.room.name) ? `ауд. ${lesson.room.name}` : lesson.room.name)}`] : []),
        `DESCRIPTION:${icsText(notes.join('\n'))}`,
        'END:VEVENT',
      );
    }
  }
  lines.push('END:VCALENDAR');
  return `${lines.map(foldLine).join('\r\n')}\r\n`;
}

// ───────────────────────── Запрошення ─────────────────────────

interface MockInvite {
  teacher: TeacherRef;
  expiresAt: IsoInstant;
  usedAt: IsoInstant | null;
}

interface InviteState {
  invites: Map<string, MockInvite>;
  /** Логіни, вже зайняті в Keycloak: демо-користувачі realm (ARCHITECTURE §6) і щойно зареєстровані. */
  usernames: Set<string>;
}

const USERNAME_RE = /^[a-z][a-z0-9._-]{2,31}$/;

/**
 * ДЕМО-запрошення: `demo` — чинне (Колесник А., ще не має облікового запису), `demo-expired` — термін минув,
 * `demo-used` — уже використане. Терміни рахуються від першого звернення, тож тести з підміненим часом працюють.
 */
const inviteState = createMockStore<InviteState | null>(() => null);

function invitesAt(now: Date): InviteState {
  const existing = inviteState.get();
  if (existing) return existing;
  const day = 86_400_000;
  const at = (offsetDays: number) => new Date(now.getTime() + offsetDays * day).toISOString();
  const seeded: InviteState = {
    invites: new Map<string, MockInvite>([
      ['demo', { teacher: teacherRef(teacherNamed('Колесник А.')), expiresAt: at(7), usedAt: null }],
      ['demo-expired', { teacher: teacherRef(teacherNamed('Паламарчук')), expiresAt: at(-1), usedAt: null }],
      ['demo-used', { teacher: teacherRef(teacherNamed('Стець')), expiresAt: at(3), usedAt: at(-2) }],
    ]),
    usernames: new Set(['admin', 'krentsin', 'nepyivoda']),
  };
  inviteState.set(seeded);
  return seeded;
}

function inviteInfo(invite: MockInvite, now: Date): InviteInfoDto {
  return {
    valid: invite.usedAt === null && new Date(invite.expiresAt).getTime() > now.getTime(),
    teacherName: invite.teacher.shortName,
    expiresAt: invite.expiresAt,
  };
}

// ───────────────────────── Обробники ─────────────────────────

const SEGMENT_TO_TYPE: Readonly<Record<string, EntityType>> = { group: 'GROUP', teacher: 'TEACHER', room: 'ROOM' };

const OWNER_NOUN: Record<EntityType, string> = { GROUP: 'Групу', TEACHER: 'Викладача', ROOM: 'Аудиторію' };

/**
 * Як PublicCalendarService.bells у core: без дати — сьогодні; `weekday` — ISO 1..7 (у вихідні `slots`
 * порожні), `studyDay` за календарем, `profiles` (активні профілі) — лише в запиті без дати.
 */
function bellsFor(requested: IsoDate | null, now: Date): BellsDto {
  const profile = { id: ACTIVE_BELL_PROFILE.id, name: ACTIVE_BELL_PROFILE.name };
  const date = requested ?? todayInKyiv(now);
  const weekday = isoWeekday(date);
  return {
    profile,
    date,
    weekday,
    studyDay: isStudyDay(date),
    slots: weekday <= 5 ? bellSlots(weekday as Weekday) : [],
    profiles: requested === null ? BELL_PROFILES.filter((p) => p.active) : null,
  };
}

export const handlers = [
  // порядок — як у друкованому розкладі: за курсами, усередині курсу — за відділеннями
  http.get(apiPath('/public/groups'), () => HttpResponse.json(GROUPS.filter((g) => g.active).map(toPublicGroup))),

  http.get(apiPath('/public/teachers'), () =>
    HttpResponse.json(TEACHERS.filter((t) => t.active).map(toPublicTeacher)),
  ),

  http.get(apiPath('/public/rooms'), () => HttpResponse.json(ROOMS.filter((r) => r.active).map(toPublicRoom))),

  http.get(apiPath('/public/bells'), ({ request }) => {
    const raw = new URL(request.url).searchParams.get('date');
    if (raw !== null && !isIsoDate(raw)) {
      return badRequest('Дата має бути у форматі РРРР-ММ-ДД.', [{ field: 'date', message: 'Формат РРРР-ММ-ДД' }]);
    }
    return HttpResponse.json(bellsFor(raw, new Date()));
  }),

  http.get(apiPath('/public/schedule/:type/:id'), ({ params, request }) => {
    const type = SEGMENT_TO_TYPE[String(params.type)];
    if (!type) return notFound(`Невідомий тип розкладу «${String(params.type)}».`);
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
    const now = new Date();
    const schedule = buildSchedule(type, String(params.id), from, to, { overlay: publicOverlay(now) });
    if (!schedule) return notFound(`${OWNER_NOUN[type]} з id ${String(params.id)} не знайдено.`);
    return HttpResponse.json(schedule);
  }),

  http.get(apiPath('/public/changes'), ({ request }) => {
    const date = new URL(request.url).searchParams.get('date');
    if (!isIsoDate(date)) {
      return badRequest('Вкажіть дату у форматі РРРР-ММ-ДД.', [{ field: 'date', message: 'Формат РРРР-ММ-ДД' }]);
    }
    return HttpResponse.json(publicChanges(date, new Date()));
  }),

  http.get(apiPath('/public/ics/:type/:file'), ({ params }) => {
    const type = SEGMENT_TO_TYPE[String(params.type)];
    const file = String(params.file);
    if ((type !== 'GROUP' && type !== 'TEACHER') || !file.endsWith('.ics')) {
      return notFound('Календар є лише для груп і викладачів: /public/ics/{group|teacher}/{id}.ics.');
    }
    const owner = scheduleOwner(type, file.slice(0, -'.ics'.length));
    if (!owner) return notFound(`${OWNER_NOUN[type]} для календаря не знайдено.`);
    return new HttpResponse(icsCalendar(owner, new Date()), {
      headers: {
        'Content-Type': 'text/calendar; charset=utf-8',
        'Content-Disposition': `attachment; filename="rozklad.ics"`,
      },
    });
  }),

  http.get(apiPath('/public/invites/:token'), ({ params }) => {
    const now = new Date();
    const invite = invitesAt(now).invites.get(String(params.token));
    if (!invite) return notFound('Запрошення не знайдено. Перевірте посилання або попросіть диспетчера надіслати нове.');
    return HttpResponse.json(inviteInfo(invite, now));
  }),

  http.post(apiPath('/public/invites/:token/accept'), async ({ params, request }) => {
    const now = new Date();
    const state = invitesAt(now);
    const token = String(params.token);
    const invite = state.invites.get(token);
    if (!invite) return notFound('Запрошення не знайдено. Перевірте посилання або попросіть диспетчера надіслати нове.');
    if (invite.usedAt) return problem({ status: 410, detail: 'Це запрошення вже використали. Увійдіть зі своїм логіном.' });
    if (new Date(invite.expiresAt).getTime() <= now.getTime()) {
      return problem({ status: 410, detail: 'Термін дії запрошення минув. Попросіть диспетчера надіслати нове.' });
    }
    const body = await readJson<Partial<AcceptInviteRequest>>(request);
    const username = typeof body.username === 'string' ? body.username.trim() : '';
    const password = typeof body.password === 'string' ? body.password : '';
    const errors = [
      ...(USERNAME_RE.test(username)
        ? []
        : [{ field: 'username', message: 'Від 3 до 32 символів: латинські малі літери, цифри, крапка, дефіс, підкреслення; першою — літера' }]),
      ...(password.length >= 8 && password.length <= 128 ? [] : [{ field: 'password', message: 'Пароль — від 8 до 128 символів' }]),
    ];
    if (errors.length > 0) return badRequest('Перевірте логін і пароль.', errors);
    if (state.usernames.has(username)) {
      return problem({
        status: 409,
        detail: `Логін «${username}» уже зайнятий.`,
        errors: [{ field: 'username', message: 'Цей логін уже зайнятий — оберіть інший' }],
      });
    }
    state.usernames.add(username);
    state.invites.set(token, { ...invite, usedAt: now.toISOString() });
    return HttpResponse.json({ username }, { status: 201 });
  }),
];
