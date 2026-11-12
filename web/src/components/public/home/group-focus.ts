import type { DayDto, IsoDate, LessonView } from '@/api/types';
import type { PairStatus } from '@/lib/bells';
import { formatWeekdayDateAfterNa, relativeDayLabel } from '@/lib/date';

export interface GroupFocusPlan {
  /** Заняття однієї пари (підгрупи — до двох рядків). */
  lessons: LessonView[];
  /** Що стоїть після «У 4КІ1»: «зараз», «далі», «далі о 13:30», «завтра о 08:30», «у пʼятницю о 09:40». */
  when: string;
}

/** Заняття групи на одній парі, без скасованих; підгрупи по порядку, не більше двох. */
function lessonsAt(day: DayDto, pair: number): LessonView[] {
  return day.lessons
    .filter((l) => l.pair === pair && l.kind !== 'CANCELLED')
    .sort((a, b) => a.subgroup - b.subgroup)
    .slice(0, 2);
}

/** Перша пара групи після `afterPair` (або взагалі перша) — номер пари. */
function firstPairAfter(day: DayDto | undefined, afterPair: number): number | null {
  const pairs = (day?.lessons ?? []).filter((l) => l.kind !== 'CANCELLED' && l.pair > afterPair).map((l) => l.pair);
  return pairs.length > 0 ? Math.min(...pairs) : null;
}

/**
 * Що показати в герої про запамʼятовану групу, поки йде навчальний день (пара, перерва, ранок до пар):
 * 1) пара групи на поточному чи наступному дзвінку — «зараз» / «далі»;
 * 2) інакше її наступне заняття сьогодні — «далі о 13:30» (у 2КІ1 перша лише 5-та, а дзвонить на 3-тю);
 * 3) у групи на сьогодні все (або пар не було) — перша пара наступного навчального дня.
 * Після пар і у вихідний героєві вистачає речення `afterDay`, тож тут — нічого.
 */
export function groupFocusPlan({
  status,
  groupToday,
  groupNextDay,
  today,
  nextDay,
}: {
  status: PairStatus | null;
  groupToday: DayDto | undefined;
  groupNextDay: DayDto | undefined;
  today: IsoDate;
  nextDay: IsoDate | null;
}): GroupFocusPlan | null {
  if (!groupToday || !status) return null;
  if (status.kind !== 'in-pair' && status.kind !== 'break' && status.kind !== 'before-day') return null;

  const bellPair = status.kind === 'in-pair' ? status.current.pair : status.next.pair;
  const atBell = lessonsAt(groupToday, bellPair);
  if (atBell.length > 0) return { lessons: atBell, when: status.kind === 'in-pair' ? 'зараз' : 'далі' };

  // на цьому дзвінку в групи пари немає — її найближча пізніша сьогодні
  const laterPair = firstPairAfter(groupToday, bellPair);
  if (laterPair !== null) {
    const lessons = lessonsAt(groupToday, laterPair);
    const startsAt = lessons[0]?.startsAt;
    return { lessons, when: startsAt ? `далі о\u00a0${startsAt}` : 'далі' };
  }

  if (!nextDay || !groupNextDay) return null;
  const firstNext = firstPairAfter(groupNextDay, 0);
  if (firstNext === null) return null;
  const lessons = lessonsAt(groupNextDay, firstNext);
  const day = relativeDayLabel(nextDay, today) === 'Завтра' ? 'завтра' : `у ${formatWeekdayDateAfterNa(nextDay).replace(/,.*$/, '')}`;
  const startsAt = lessons[0]?.startsAt;
  return { lessons, when: startsAt ? `${day} о\u00a0${startsAt}` : day };
}
