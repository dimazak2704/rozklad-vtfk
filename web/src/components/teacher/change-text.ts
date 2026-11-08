/** Тексти для карток запитів на заміну: що саме треба провести і замість кого. */
import type { ChangeDto, IsoDate } from '@/api/types';
import type { BadgeTone } from '@/components/ui';
import { compareIsoDates, formatWeekdayDate, relativeDayLabel, todayInKyiv } from '@/lib/date';
import { REASON_CODE_LABEL } from '@/lib/labels';
import { roomLabel } from '@/lib/schedule';

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

/** «Відп.» або власний текст причини. */
export function changeReason(change: ChangeDto): string | null {
  return change.reasonCode === 'OTHER' ? change.reasonText : REASON_CODE_LABEL[change.reasonCode];
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
    if (successor.kind === 'REPLACE_TEACHER' || successor.kind === 'REPLACE_DISCIPLINE') {
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

/** Повідомлення NOTIFY, на яке ще не відповіли: заміна вже діє, викладач може сказати «Добре» чи «Не можу». */
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
