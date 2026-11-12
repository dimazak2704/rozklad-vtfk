/** Тексти для карток запитів на заміну: що саме треба провести і замість кого. */
import type { ChangeDto, IsoDate, Uuid } from '@/api/types';
import type { BadgeTone } from '@/components/ui';
import { compareIsoDates, formatDayMonth, formatWeekdayDate, isoWeekday, relativeDayLabel, todayInKyiv } from '@/lib/date';
import { weekdayShort } from '@/lib/labels';
import { roomLabel } from '@/lib/schedule';

/** Повна назва, поки вона коротка («Фізичне виховання»); довгу заміняє скорочення («СТП»). */
const TITLE_MAX = 32;

/** Яку дисципліну проводить новий викладач: нову (взаємна заміна) або ту саму. */
export function changeDiscipline(change: ChangeDto): string {
  return (change.new.discipline ?? change.original.discipline)?.shortName ?? 'Пара';
}

export function changeDisciplineName(change: ChangeDto): string | null {
  const discipline = change.new.discipline ?? change.original.discipline;
  return discipline && discipline.name !== discipline.shortName ? discipline.name : null;
}

export function changeRoom(change: ChangeDto): string | null {
  return roomLabel((change.new.room ?? change.original.room)?.name);
}

/** Назва для рядка заміни: повна, якщо вміщається, інакше скорочення. */
export function changeDisciplineTitle(change: ChangeDto): string {
  const discipline = change.new.discipline ?? change.original.discipline;
  if (!discipline) return 'Пара';
  return discipline.name && discipline.name.length <= TITLE_MAX ? discipline.name : discipline.shortName;
}

/** Кого (і що) я заміняю: «Паламарчук» або «КБ, Непийвода», коли дисципліна теж інша. */
export function changeReplaced(change: ChangeDto): string | null {
  const swapped = change.kind === 'REPLACE_DISCIPLINE' ? change.original.discipline?.shortName : null;
  return [swapped, change.original.teacher?.displayName].filter(Boolean).join(', ') || null;
}

/** «Сьогодні», «Завтра» або «Пт, 13 листопада» — коротко для колонки часу. */
export function changeWhen(change: ChangeDto, today: string = todayInKyiv()): string {
  return relativeDayLabel(change.date, today) ?? `${weekdayShort(isoWeekday(change.date))}, ${formatDayMonth(change.date)}`;
}

/** Хвіст строку відповіді: «» (сьогодні), « завтра» або « 12 листопада». */
export function deadlineDay(deadline: Date, today: string): string {
  const day = todayInKyiv(deadline);
  if (day === today) return '';
  const relative = relativeDayLabel(day, today);
  return relative ? ` ${relative.toLocaleLowerCase('uk')}` : `, ${formatDayMonth(day)}`;
}

/** «Завтра, 10 листопада» або «Середа, 11 листопада». */
export function changeDay(change: ChangeDto, today: string = todayInKyiv()): string {
  const relative = relativeDayLabel(change.date, today);
  const full = formatWeekdayDate(change.date);
  return relative ? `${relative}, ${full.split(', ')[1] ?? full}` : full;
}

/** Підпис для aria/тосту: «4КІ2, завтра, 2 пара». */
export function changeTitle(change: ChangeDto, today?: string): string {
  return `${change.group.code}, ${changeDay(change, today).toLocaleLowerCase('uk')}, ${change.pairFrom} пара`;
}

const STATUS: Partial<Record<ChangeDto['status'], { label: string; tone: BadgeTone }>> = {
  CONFIRMED: { label: 'Підтверджено', tone: 'success' },
  APPLIED: { label: 'У розпорядженні', tone: 'accent' },
};

/**
 * Стан заміни для викладача. Застосовану заміну могло пізніше скасувати чи передати іншому доповнення:
 * тоді вона лишається APPLIED (історія), але пари в мене вже немає — так само каже й розклад дня.
 */
export function changeStatusBadge(change: ChangeDto): { label: string; tone: BadgeTone } | undefined {
  const successor = change.supersededBy;
  if (change.status === 'APPLIED' && successor) {
    if (successor.kind === 'CANCEL') return { label: 'Скасовано доповненням', tone: 'cancel' };
    if (successor.kind === 'REPLACE_TEACHER' || successor.kind === 'REPLACE_DISCIPLINE' || successor.kind === 'MERGE_SUBGROUPS') {
      return { label: 'Передано іншому доповненням', tone: 'cancel' };
    }
    return { label: 'Змінено доповненням', tone: 'change' };
  }
  // NOTIFY: «Не можу» після публікації — заміна ще в розпорядженні, доки диспетчер не внесе доповнення
  if (change.status === 'APPLIED' && change.teacherAnswer === 'DECLINE' && change.approvalMode === 'NOTIFY') {
    return { label: 'Ви повідомили, що не можете', tone: 'cancel' };
  }
  return STATUS[change.status];
}

function byDateAndPair(a: ChangeDto, b: ChangeDto): number {
  return compareIsoDates(a.date, b.date) || a.pairFrom - b.pairFrom;
}

/** Повідомлення NOTIFY, на яке ще не відповіли: заміна вже діє, викладач може відповісти «Підтверджую» чи «Не можу». */
export function isUnansweredNotice(change: ChangeDto, today: IsoDate): boolean {
  return (
    change.approvalMode === 'NOTIFY' &&
    !change.teacherAnswer &&
    (change.status === 'CONFIRMED' || change.status === 'APPLIED') &&
    compareIsoDates(change.date, today) >= 0
  );
}

/** Що чекає моєї відповіді: прохання (CONFIRM) і нові заміни, про які мені повідомили (NOTIFY). */
export function awaitingAnswer(changes: readonly ChangeDto[], today: IsoDate): ChangeDto[] {
  return changes.filter((c) => c.status === 'PENDING_CONFIRMATION' || isUnansweredNotice(c, today)).sort(byDateAndPair);
}

/** Скільки часу є, щоб передумати: стільки ж живе сповіщення з «Скасувати». */
export const REPLY_UNDO_MS = 8_000;

/**
 * Найтерміновіший рядок — єдиний із заповненою кнопкою: прохання з найближчим строком відповіді (строк
 * стоїть першим рядком і пояснює, чому саме воно). Немає відкритих строків — немає й заповненої кнопки:
 * без пояснення вона лише вносила б нерівність між однаковими рядками.
 */
export function mostUrgent(waiting: readonly ChangeDto[], now: Date): Uuid | null {
  const open = waiting
    .filter((c) => c.status === 'PENDING_CONFIRMATION' && c.confirmDeadline && new Date(c.confirmDeadline).getTime() > now.getTime())
    .sort((a, b) => new Date(a.confirmDeadline ?? 0).getTime() - new Date(b.confirmDeadline ?? 0).getTime());
  return open[0]?.id ?? null;
}
