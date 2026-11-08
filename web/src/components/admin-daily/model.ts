/**
 * Модель екранів щоденної роботи: стан пари за рішенням, групування зачеплених пар, строки підтвердження,
 * запит на зміну з обраного варіанта, стрічка подій. Чисті функції — легко тестувати й перевикористати.
 */
import type { AffectedLesson, ChangeOption } from '@/api/admin-daily';
import type {
  AbsenceDto,
  AbsenceReasonCategory,
  ApprovalMode,
  ChangeDto,
  ChangeKind,
  ChangeStatus,
  CreateChangeRequest,
  IsoDate,
  IsoInstant,
  LessonView,
  OrderSummaryDto,
  ReasonCode,
  TeacherAvailabilityDto,
  TeacherAvailabilityStatus,
  Uuid,
} from '@/api/types';
import type { BadgeTone } from '@/components/ui';
import { countLabel, durationLabel } from '@/lib/labels';

// ───────────────────────── Стан пари ─────────────────────────

export type LessonState =
  | 'uncovered'
  | 'declined'
  | 'substituteAbsent'
  | 'objected'
  | 'pending'
  | 'confirmed'
  | 'cancelled'
  | 'applied';

export const LESSON_STATE: Record<LessonState, { label: string; tone: BadgeTone; needsAction: boolean }> = {
  uncovered: { label: 'Без рішення', tone: 'danger', needsAction: true },
  declined: { label: 'Відмова', tone: 'danger', needsAction: true },
  substituteAbsent: { label: 'Замінник відсутній', tone: 'danger', needsAction: true },
  objected: { label: 'Потрібне доповнення', tone: 'danger', needsAction: true },
  pending: { label: 'Чекає відповіді', tone: 'warning', needsAction: false },
  confirmed: { label: 'Підтверджено', tone: 'success', needsAction: false },
  cancelled: { label: 'Пари не буде', tone: 'cancel', needsAction: false },
  applied: { label: 'У розпорядженні', tone: 'accent', needsAction: false },
};

type Decision = { status: ChangeStatus; kind: ChangeKind } | null | undefined;

/**
 * Що з парою зараз: скасоване рішення означає «знову без рішення», відхилене — «потрібен інший».
 * `asSubstitute`: відсутній викладач мав замінити на цій парі іншого — неопубліковане рішення вже не
 * відбудеться (core не дасть його підтвердити чи опублікувати), його треба скасувати.
 */
export function lessonState(decision: Decision, asSubstitute = false): LessonState {
  if (!decision) return 'uncovered';
  if (asSubstitute && (decision.status === 'DRAFT' || decision.status === 'PENDING_CONFIRMATION' || decision.status === 'CONFIRMED')) {
    return 'substituteAbsent';
  }
  switch (decision.status) {
    case 'CANCELLED':
      return 'uncovered';
    case 'DECLINED':
      return 'declined';
    case 'DRAFT':
    case 'PENDING_CONFIRMATION':
      return 'pending';
    case 'CONFIRMED':
      return decision.kind === 'CANCEL' ? 'cancelled' : 'confirmed';
    case 'APPLIED':
      return 'applied';
  }
}

/** Рішення ще можна змінити: на пару можна обрати інший варіант. */
export function isOpen(state: LessonState): boolean {
  return state === 'uncovered' || state === 'declined';
}

// ───────────────────────── Зачеплені пари ─────────────────────────

export interface LessonEntry {
  absence: AbsenceDto;
  item: AffectedLesson;
  state: LessonState;
  change: ChangeDto | null;
}

export interface TeacherGroup {
  absence: AbsenceDto;
  days: Array<{ date: IsoDate; entries: LessonEntry[] }>;
}

/**
 * Заперечення після публікації (NOTIFY): викладач натиснув «Не можу», а заміна вже в розпорядженні. Core його не
 * скасовує — диспетчер готує доповнення, тож пара знову потребує дії.
 */
export function isObjection(change: Pick<ChangeDto, 'status' | 'teacherAnswer'> | null | undefined): boolean {
  return change?.status === 'APPLIED' && change.teacherAnswer === 'DECLINE';
}

export function toEntries(absence: AbsenceDto, items: readonly AffectedLesson[], changes: ReadonlyMap<Uuid, ChangeDto>): LessonEntry[] {
  return items.map((item) => {
    const change = item.decision ? (changes.get(item.decision.changeId) ?? null) : null;
    const state = lessonState(item.decision, item.asSubstitute);
    return { absence, item, state: state === 'applied' && isObjection(change) ? 'objected' : state, change };
  });
}

/**
 * Чи останній запис історії — відповідь викладача (для живого тосту диспетчеру): відповідь на прохання
 * (PENDING → CONFIRMED | DECLINED) або на повідомлення NOTIFY («Добре» — статус той самий, «Не можу» до
 * розпорядження — CONFIRMED → DECLINED, після — APPLIED → APPLIED).
 */
export function teacherAnswered(from: ChangeStatus | null, to: ChangeStatus, mode: ApprovalMode): boolean {
  if (from === 'PENDING_CONFIRMATION') return to === 'CONFIRMED' || to === 'DECLINED';
  if (mode !== 'NOTIFY') return false;
  return (from === 'CONFIRMED' && (to === 'CONFIRMED' || to === 'DECLINED')) || (from === 'APPLIED' && to === 'APPLIED');
}

/**
 * Відповідь викладача на повідомлення NOTIFY для рядка й картки рішення: `null` — режим інший або відповіді ще
 * немає (у NOTIFY — `waiting`).
 */
export function noticeAnswer(change: ChangeDto | null | undefined): 'waiting' | 'ok' | 'cannot' | null {
  if (!change || change.approvalMode !== 'NOTIFY') return null;
  if (change.teacherAnswer === 'CONFIRM') return 'ok';
  if (change.teacherAnswer === 'DECLINE') return 'cannot';
  return change.status === 'CONFIRMED' || change.status === 'APPLIED' ? 'waiting' : null;
}

/** Пари за викладачем (відсутністю), усередині — за датою; порядок як у списку відсутностей. */
export function groupByTeacher(entries: readonly LessonEntry[]): TeacherGroup[] {
  const groups = new Map<Uuid, TeacherGroup>();
  for (const entry of entries) {
    let group = groups.get(entry.absence.id);
    if (!group) {
      group = { absence: entry.absence, days: [] };
      groups.set(entry.absence.id, group);
    }
    let day = group.days.find((d) => d.date === entry.item.date);
    if (!day) {
      day = { date: entry.item.date, entries: [] };
      group.days.push(day);
    }
    day.entries.push(entry);
  }
  for (const group of groups.values()) group.days.sort((a, b) => a.date.localeCompare(b.date));
  return [...groups.values()];
}

export type LessonFilter = 'all' | 'action' | 'pending' | 'declined' | 'done';

export const LESSON_FILTERS: ReadonlyArray<{ value: LessonFilter; label: string }> = [
  { value: 'all', label: 'Усі' },
  { value: 'action', label: 'Без рішення' },
  { value: 'pending', label: 'Чекають' },
  { value: 'declined', label: 'Відмови' },
  { value: 'done', label: 'Готово' },
];

export function matchesFilter(state: LessonState, filter: LessonFilter): boolean {
  switch (filter) {
    case 'all':
      return true;
    case 'action':
      return state === 'uncovered' || state === 'declined' || state === 'substituteAbsent' || state === 'objected';
    case 'pending':
      return state === 'pending';
    case 'declined':
      return state === 'declined' || state === 'objected';
    case 'done':
      return state === 'confirmed' || state === 'cancelled' || state === 'applied';
  }
}

/** Фільтр з адреси (посилання з панелі: `?filter=uncovered|pending|declined`). */
export function filterFromParam(value: string | null): LessonFilter {
  if (value === 'uncovered' || value === 'action') return 'action';
  if (value === 'pending' || value === 'declined' || value === 'done') return value;
  return 'all';
}

// ───────────────────────── Строк підтвердження ─────────────────────────

export interface PendingInfo {
  overdue: boolean;
  /** Хвилин до строку (або після нього, якщо прострочено). */
  minutes: number;
  label: string;
}

export function pendingInfo(deadline: IsoInstant | null | undefined, nowMs: number): PendingInfo | null {
  if (!deadline) return null;
  const diff = Date.parse(deadline) - nowMs;
  const minutes = Math.max(0, Math.round(Math.abs(diff) / 60_000));
  if (diff >= 0) return { overdue: false, minutes, label: minutes < 1 ? 'строк спливає' : `ще ${durationLabel(minutes)}` };
  return { overdue: true, minutes, label: minutes < 1 ? 'строк минув' : `прострочено ${durationLabel(minutes)}` };
}

// ───────────────────────── Варіанти → запит ─────────────────────────

/** Причина за замовчуванням: відрядження друкують «Відр.», решту — «Відп.» (DOMAIN §7). */
export function defaultReason(category: AbsenceReasonCategory | undefined): ReasonCode {
  return category === 'BUSINESS_TRIP' ? 'BUSINESS_TRIP' : 'ABSENT';
}

/**
 * Причина, яку підставляємо під обраний варіант, доки диспетчер не змінив її сам: інша дисципліна групи —
 * «Зв.зам.» (так друкують 9 з 10 таких замін), скасування — «Відп.», решта — за причиною відсутності.
 */
export function reasonForOption(kind: ChangeKind | null | undefined, category: AbsenceReasonCategory | undefined): ReasonCode {
  if (kind === 'REPLACE_DISCIPLINE') return 'MUTUAL';
  if (kind === 'CANCEL') return 'ABSENT';
  return defaultReason(category);
}

export interface PickContext {
  date: IsoDate;
  lesson: AffectedLesson['lesson'];
  absenceId: Uuid | null;
  reasonCode: ReasonCode;
  reasonText: string | null;
}

function baseRequest(kind: ChangeKind, ctx: PickContext): CreateChangeRequest {
  return {
    kind,
    date: ctx.date,
    pairFrom: ctx.lesson.pair,
    groupId: ctx.lesson.group.id,
    subgroup: ctx.lesson.subgroup,
    originalLessonId: ctx.lesson.id,
    reasonCode: ctx.reasonCode,
    reasonText: ctx.reasonCode === 'OTHER' ? ctx.reasonText : null,
    absenceId: ctx.absenceId,
  };
}

/**
 * Запит на сервер для обраного варіанта. Аудиторія — та, яку запропонував сервер (`suggestedRoom`:
 * група йде до викладача). null — варіант неповний (сервер не дав потрібних полів) або цей вид
 * зі списку варіантів не створюється (відпрацювання — окрема ручна дія).
 */
export function requestForOption(option: ChangeOption, ctx: PickContext): CreateChangeRequest | null {
  const base = baseRequest(option.kind, ctx);
  const room = option.suggestedRoom ? { newRoomId: option.suggestedRoom.id } : {};
  switch (option.kind) {
    case 'REPLACE_DISCIPLINE':
      return option.teacher && option.discipline
        ? { ...base, newTeacherId: option.teacher.id, newDisciplineId: option.discipline.id, ...room }
        : null;
    case 'MERGE_SUBGROUPS':
      // викладача й дисципліну core бере з пари іншої підгрупи: newTeacherId/newDisciplineId не надсилаємо
      return option.swapLesson ? { ...base, swapLessonId: option.swapLesson.id, ...room } : null;
    case 'SWAP':
      return option.swapLesson ? { ...base, swapLessonId: option.swapLesson.id } : null;
    case 'CANCEL':
      return base;
    case 'REPLACE_TEACHER':
      // сервер його вже не пропонує; лишається для сумісності зі старими відповідями
      return option.teacher ? { ...base, newTeacherId: option.teacher.id } : null;
    case 'ADDITIONAL':
    case 'ROOM_CHANGE':
    case 'BLOCK':
    case 'DISCIPLINE_FINISHED':
      return null;
  }
}

/** Ручне призначення іншого викладача тієї самої дисципліни (виняток, «Інший викладач…»). */
export function manualTeacherRequest(teacherId: Uuid, ctx: PickContext): CreateChangeRequest {
  return { ...baseRequest('REPLACE_TEACHER', ctx), newTeacherId: teacherId };
}

/** Стабільний ключ варіанта (для списків, вибору й стану «призначаємо…»). */
export function optionKey(option: ChangeOption): string {
  return [option.kind, option.teacher?.id, option.discipline?.id, option.swapLesson?.id].join('|');
}

/** id кнопки пари в списку: за ним J/K переводять фокус і прокручують список. */
export function lessonButtonId(entry: { item: { date: IsoDate; lesson: { id: Uuid } } }): string {
  return `lesson-${entry.item.date}-${entry.item.lesson.id}`;
}

/** id кнопки вибору варіанта: клавіші 1–9 обирають варіант і переводять на нього фокус (Enter — дія). */
export function optionButtonId(lessonId: Uuid, index: number): string {
  return `option-${lessonId}-${index}`;
}

// ───────────────────────── Подання варіантів ─────────────────────────

/** Скільки варіантів видно одразу; решта — за «Ще варіанти». */
export const VISIBLE_OPTIONS = 3;
/** Чипів у рядку не більше трьох: перша причина, перше «⚠», ще один. */
export const MAX_ROW_CHIPS = 3;

/** Підпис виду над назвою варіанта. */
export const OPTION_KIND_LABEL: Partial<Record<ChangeKind, string>> = {
  REPLACE_DISCIPLINE: 'Інша дисципліна групи',
  MERGE_SUBGROUPS: 'Обʼєднання підгруп',
  SWAP: 'Перестановка',
  CANCEL: 'Скасування',
  REPLACE_TEACHER: 'Інший викладач',
};

/** Назва варіанта. Обʼєднання формулюємо як дію викладача: «Обʼєднати підгрупи — Можаровська веде обидві». */
export function optionTitle(option: ChangeOption): string {
  if (option.kind === 'MERGE_SUBGROUPS' && option.teacher) return `Обʼєднати підгрупи — ${option.teacher.shortName} веде обидві`;
  return option.title;
}

export interface OptionChip {
  text: string;
  warning: boolean;
}

/** Чипи рядка: перша причина, перше «⚠», далі ще один (наступна причина або «⚠»). Решта — у «Чому?». */
export function rowChips(option: ChangeOption): OptionChip[] {
  const reasons = option.reasons.map((text) => ({ text, warning: false }));
  const warnings = option.warnings.map((text) => ({ text, warning: true }));
  const chips: OptionChip[] = [];
  const firstReason = reasons.shift();
  const firstWarning = warnings.shift();
  if (firstReason) chips.push(firstReason);
  if (firstWarning) chips.push(firstWarning);
  for (const chip of [...reasons, ...warnings]) {
    if (chips.length >= MAX_ROW_CHIPS) break;
    chips.push(chip);
  }
  return chips;
}

/** Чи призначає варіант викладача (тоді в режимі «з підтвердженням» йому йде прохання в Telegram). */
export function assignsTeacher(option: Pick<ChangeOption, 'kind' | 'teacher'>): boolean {
  return option.kind !== 'CANCEL' && option.teacher !== null;
}

/**
 * Напис кнопки дії. Прохання надсилаємо лише в режимі CONFIRM; будь-який інший режим (AUTO, NOTIFY)
 * призначає одразу. Поки налаштування не завантажились — теж «Призначити»: це лише напис, статус
 * зміни визначає сервер.
 */
export function actionLabel(option: Pick<ChangeOption, 'kind' | 'teacher'>, approvalMode: string | null | undefined): string {
  return assignsTeacher(option) ? assignLabel(approvalMode) : 'Застосувати';
}

/** Напис дії, що призначає викладача: «Надіслати прохання» лише в режимі CONFIRM. */
export function assignLabel(approvalMode: string | null | undefined): string {
  return approvalMode === 'CONFIRM' ? 'Надіслати прохання' : 'Призначити';
}

/** Напис кнопки плану дня: «Надіслати всі (3)» у режимі CONFIRM, інакше «Призначити всі (3)». */
export function planActionLabel(count: number, approvalMode: string | null | undefined): string {
  return `${approvalMode === 'CONFIRM' ? 'Надіслати всі' : 'Призначити всі'} (${count})`;
}

/** Заголовок над списком: рекомендація є лише тоді, коли сервер явно позначив перший варіант. */
export function optionsHeading(options: readonly ChangeOption[], past: boolean): string | null {
  if (past) return 'Заняття вже минуло — позначте, що було насправді.';
  if (options.length > 1 && !options[0]?.recommended) return 'Рівноцінні варіанти — оберіть самі';
  return null;
}

/** У групи немає вільного викладача іншої дисципліни й нікого, хто взяв би обидві підгрупи. */
export function poolIsEmpty(options: readonly ChangeOption[]): boolean {
  return !options.some((option) => option.kind === 'REPLACE_DISCIPLINE' || option.kind === 'MERGE_SUBGROUPS');
}

export const EMPTY_POOL_TEXT = 'Вільних викладачів групи на цю пару немає. Можна переставити або скасувати пару.';

/** Стан викладача на парі словами (рід невідомий — обидві форми, як у бланках коледжу). */
export function availabilityLabel(item: Pick<TeacherAvailabilityDto, 'status' | 'detail'>): string {
  const labels: Record<TeacherAvailabilityStatus, string> = {
    FREE: 'Вільний(а)',
    BUSY: item.detail ? `Зайнятий(а): ${item.detail}` : 'Зайнятий(а)',
    ABSENT: 'Відсутній(я)',
    UNAVAILABLE: 'Недоступний(а) в цей час',
    DECLINED: item.detail ? `Відмовився(лась) о ${item.detail}` : 'Відмовився(лась)',
  };
  return labels[item.status];
}

/** Обране в плані: пара й варіант (без «не вирішувати»). */
export interface PlanChoice {
  lesson: LessonView;
  option: ChangeOption;
}

const REQUEST_FORMS = ['прохання', 'прохання', 'прохань'] as const;

/** Підсумок перед дією: кому піде прохання (CONFIRM) чи кого призначимо, і скільки пар скасуємо. */
export function planSummary(choices: readonly PlanChoice[], approvalMode: string | null | undefined): string[] {
  const teachers = choices.filter((c) => assignsTeacher(c.option)).map((c) => c.option.teacher?.shortName ?? '');
  const cancels = choices.filter((c) => c.option.kind === 'CANCEL').length;
  const lines: string[] = [];
  if (teachers.length > 0) {
    lines.push(
      approvalMode === 'CONFIRM'
        ? `${countLabel(teachers.length, REQUEST_FORMS)} в Telegram: ${teachers.join(', ')}`
        : `Призначимо: ${teachers.join(', ')}`,
    );
  }
  if (cancels > 0) lines.push(`Пар не буде: ${cancels}`);
  return lines;
}

// ───────────────────────── Стрічка подій ─────────────────────────

export type FeedKind = 'absence' | 'request' | 'confirmed' | 'declined' | 'cancelled' | 'applied' | 'reminder' | 'order';

export interface FeedItem {
  id: string;
  /** id зміни, відсутності чи розпорядження, з якого подія (для підсвічування живих оновлень). */
  sourceId: Uuid;
  at: IsoInstant;
  kind: FeedKind;
  text: string;
  /** Куди веде пункт (сторінка й параметри). */
  href: string | null;
}

function changeLine(change: ChangeDto): string {
  const discipline = change.original.discipline?.shortName ?? change.new.discipline?.shortName ?? '';
  return `${change.group.code}, ${change.pairFrom} пара${discipline ? ` (${discipline})` : ''}`;
}

/** Події дня зі змін (історія станів), відсутностей і опублікованих розпоряджень — найновіші першими. */
export function buildFeed(changes: readonly ChangeDto[], absences: readonly AbsenceDto[], orders: readonly OrderSummaryDto[], date: IsoDate): FeedItem[] {
  const items: FeedItem[] = [];
  const changesHref = `/admin/changes?date=${date}`;
  for (const absence of absences) {
    if (!absence.createdAt) continue;
    const via = absence.source === 'BOT' ? ' (через Telegram-бота)' : absence.source === 'WEB' ? ' (з кабінету)' : '';
    items.push({
      id: `absence:${absence.id}`,
      sourceId: absence.id,
      at: absence.createdAt,
      kind: 'absence',
      text: `Відсутність: ${absence.teacher.shortName}${via}`,
      href: `${changesHref}&absence=${absence.id}`,
    });
  }
  for (const change of changes) {
    change.history.forEach((entry, index) => {
      const teacher = change.new.teacher?.shortName ?? 'викладач';
      let kind: FeedKind | null = null;
      let text = '';
      if (entry.from === 'APPLIED' && entry.to === 'APPLIED') {
        // NOTIFY: «Не можу» після публікації — розпорядження лишається, потрібне доповнення
        kind = 'declined';
        text = `Не може, потрібне доповнення: ${teacher} — ${changeLine(change)}`;
      } else if (entry.from === 'CONFIRMED' && entry.to === 'CONFIRMED') {
        kind = 'confirmed';
        text = `«Добре»: ${teacher} — ${changeLine(change)}`;
      } else if (entry.from === null && entry.to === 'CONFIRMED' && change.approvalMode === 'NOTIFY') {
        kind = 'confirmed';
        text = `Призначено: ${teacher} — ${changeLine(change)}`;
      } else if (entry.to === 'PENDING_CONFIRMATION' && entry.from === 'PENDING_CONFIRMATION') {
        kind = 'reminder';
        text = `Нагадування в Telegram: ${teacher} — ${changeLine(change)}`;
      } else if (entry.to === 'PENDING_CONFIRMATION') {
        kind = 'request';
        text = `Запит у Telegram: ${teacher} — ${changeLine(change)}`;
      } else if (entry.to === 'CONFIRMED') {
        kind = 'confirmed';
        text =
          change.kind === 'CANCEL'
            ? `Пари не буде: ${changeLine(change)}`
            : entry.actor === teacher
              ? `Підтверджено в Telegram: ${teacher} — ${changeLine(change)}`
              : `Підтверджено: ${teacher} — ${changeLine(change)}`;
      } else if (entry.to === 'DECLINED') {
        kind = 'declined';
        text = `Відмова: ${teacher} — ${changeLine(change)}`;
      } else if (entry.to === 'CANCELLED') {
        kind = 'cancelled';
        text = `Рішення скасовано: ${changeLine(change)}`;
      }
      if (kind) items.push({ id: `change:${change.id}:${index}`, sourceId: change.id, at: entry.at, kind, text, href: changesHref });
    });
  }
  for (const order of orders) {
    if (order.status !== 'PUBLISHED' || !order.publishedAt) continue;
    items.push({
      id: `order:${order.id}`,
      sourceId: order.id,
      at: order.publishedAt,
      kind: 'order',
      text: `${order.kind === 'AMENDMENT' ? 'Доповнення' : 'Розпорядження'} №${order.number ?? '—'} опубліковано`,
      href: `/admin/orders?date=${order.date}`,
    });
  }
  return items.sort((a, b) => b.at.localeCompare(a.at) || a.id.localeCompare(b.id));
}
