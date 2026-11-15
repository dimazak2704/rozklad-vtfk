/**
 * План годин за рішенням власника 15.11.2026 — як у core (calendar StudyCalendar.theory, load LoadPlan): тижні, коли
 * група не на теорії (вихідні DAY_OFF і блоки SUSPEND: НС, практика, сесія, збори, канікули), не плануються й не
 * надолужуються. З парами опублікованого шаблону план точний: кожна пара рахує навчальні дні групи свого дня тижня
 * в тижні своєї парності (пара лише по чисельнику втрачає лише тижні чисельника), а число масштабується до годин
 * навантаження: `2 × годин на тиждень × пари в дні теорії / пари шаблону за два тижні`. Без пар — години на
 * тиждень × навчальні дні / 5.
 */
import type { CalendarPeriod, CalendarSemester } from '@/api/calendar';
import type { LoadItem } from '@/api/load';
import type { GroupOffPeriodDto, GroupRef, GroupTheoryDto, IsoDate, TemplateLessonDto, Uuid, WeekParity } from '@/api/types';
import { addDays, compareIsoDates, diffDays, isoWeekday, startOfWeek } from '@/lib/date';
import { closesLessons, TEMPLATE_LESSONS } from './fixtures';
import { parityOfDate, periods } from './handlers/calendar';

function touchesGroup(period: CalendarPeriod, group: Pick<GroupRef, 'id' | 'course'>): boolean {
  switch (period.scope) {
    case 'ALL':
      return true;
    case 'COURSES':
      return (period.courses ?? []).includes(group.course);
    case 'GROUPS':
      return (period.groupIds ?? []).includes(group.id);
  }
}

export interface MockGroupTheory extends GroupTheoryDto {
  /** `${weekday}|${parity}` → навчальні дні групи. */
  days: Map<string, number>;
}

interface SemesterDay {
  date: IsoDate;
  weekday: number;
  parity: WeekParity;
  weekStart: IsoDate;
}

/** Пн–пт семестру з парністю тижня — один раз на семестр (парність тижня рахується раз на тиждень). */
function semesterDays(semester: CalendarSemester): SemesterDay[] {
  const out: SemesterDay[] = [];
  const parities = new Map<IsoDate, WeekParity>();
  for (let date = semester.startsOn; compareIsoDates(date, semester.endsOn) <= 0; date = addDays(date, 1)) {
    const weekday = isoWeekday(date);
    if (weekday > 5) continue;
    const weekStart = startOfWeek(date);
    let parity = parities.get(weekStart);
    if (!parity) {
      parity = parityOfDate(semester, date);
      parities.set(weekStart, parity);
    }
    out.push({ date, weekday, parity, weekStart });
  }
  return out;
}

/** Навчальні дні групи в семестрі: пн–пт, без періодів, що знімають її з пар. */
export function groupTheory(
  semester: CalendarSemester,
  group: GroupRef,
  graphTheoryWeeks: number | null = null,
  weekdays: readonly SemesterDay[] = semesterDays(semester),
): MockGroupTheory {
  const closing = periods.all().filter((p) => p.semesterId === semester.id && closesLessons(p) && touchesGroup(p, group));
  const days = new Map<string, number>();
  const numerator = new Set<IsoDate>();
  const denominator = new Set<IsoDate>();
  const taking = new Set<CalendarPeriod>();
  let studyDays = 0;
  for (const { date, weekday, parity, weekStart } of weekdays) {
    const closedBy = closing.filter((p) => compareIsoDates(date, p.startsOn) >= 0 && compareIsoDates(date, p.endsOn) <= 0);
    if (closedBy.length > 0) {
      closedBy.forEach((p) => taking.add(p));
      continue;
    }
    days.set(`${weekday}|${parity}`, (days.get(`${weekday}|${parity}`) ?? 0) + 1);
    (parity === 'NUMERATOR' ? numerator : denominator).add(weekStart);
    studyDays += 1;
  }
  const offPeriods: GroupOffPeriodDto[] = [...taking]
    .sort((a, b) => compareIsoDates(a.startsOn, b.startsOn) || a.title.localeCompare(b.title, 'uk'))
    .map((p) => ({
      id: p.id,
      kind: {
        code: p.periodKind?.code ?? '',
        name: p.periodKind?.name ?? p.title,
        color: p.periodKind?.color ?? null,
        effect: p.periodKind?.effect ?? 'SUSPEND',
      },
      title: p.title,
      startsOn: p.startsOn,
      endsOn: p.endsOn,
    }));
  return {
    group,
    semesterWeeks: Math.floor(diffDays(startOfWeek(semester.startsOn), startOfWeek(semester.endsOn)) / 7) + 1,
    theoryWeeks: numerator.size + denominator.size,
    numeratorWeeks: numerator.size,
    denominatorWeeks: denominator.size,
    studyDays,
    graphTheoryWeeks,
    offPeriods,
    days,
  };
}

const daysOf = (theory: MockGroupTheory, weekday: number, parity: WeekParity | 'BOTH') =>
  parity === 'BOTH'
    ? (theory.days.get(`${weekday}|NUMERATOR`) ?? 0) + (theory.days.get(`${weekday}|DENOMINATOR`) ?? 0)
    : (theory.days.get(`${weekday}|${parity}`) ?? 0);

let slotIndex: Map<string, TemplateLessonDto[]> | null = null;

/** Пари опублікованого шаблону, що належать запису (уся група або підгрупи — за поділом). */
function slotsOf(item: Pick<LoadItem, 'group' | 'discipline' | 'splitSubgroups'>): TemplateLessonDto[] {
  if (!slotIndex) {
    slotIndex = new Map();
    for (const lesson of TEMPLATE_LESSONS) {
      const key = `${lesson.group.id}|${lesson.discipline.id}`;
      slotIndex.set(key, [...(slotIndex.get(key) ?? []), lesson]);
    }
  }
  return (slotIndex.get(`${item.group.id}|${item.discipline.id}`) ?? []).filter((l) => l.subgroup > 0 === item.splitSubgroups);
}

/** Автоматичний план запису (AUTO) за днями теорії групи. */
export function autoPlan(
  item: Pick<LoadItem, 'group' | 'discipline' | 'splitSubgroups' | 'hoursPerWeek'>,
  theory: MockGroupTheory,
): number {
  const slots = slotsOf(item);
  if (slots.length === 0) return Math.round((item.hoursPerWeek * theory.studyDays) / 5);
  const cohorts = new Map<number, TemplateLessonDto[]>();
  for (const slot of slots) cohorts.set(slot.subgroup, [...(cohorts.get(slot.subgroup) ?? []), slot]);
  let best = 0;
  for (const cohort of cohorts.values()) {
    const perCycle = cohort.reduce((sum, l) => sum + (l.parity === 'BOTH' ? 2 : 1), 0);
    const theoryPairs = cohort.reduce((sum, l) => sum + daysOf(theory, l.weekday, l.parity), 0);
    best = Math.max(best, Math.round((2 * item.hoursPerWeek * theoryPairs) / perCycle));
  }
  return best;
}

/** Тижні теорії груп з кешем на один запит (група → дні). */
export function theoryCache(semester: CalendarSemester): (group: GroupRef) => MockGroupTheory {
  const cache = new Map<Uuid, MockGroupTheory>();
  let weekdays: SemesterDay[] | null = null;
  return (group) => {
    const cached = cache.get(group.id);
    if (cached) return cached;
    weekdays ??= semesterDays(semester);
    const theory = groupTheory(semester, group, GRAPH_THEORY_WEEKS[group.code] ?? null, weekdays);
    cache.set(group.id, theory);
    return theory;
  };
}

/**
 * Тижні теорії з графіка освітнього процесу 2026/27 (колонка «К-сть тижн. теор. навч.») для кількох груп — лише
 * для звірки: у 4 курсу семестр графіка довший (до 14.02), тому число більше.
 */
export const GRAPH_THEORY_WEEKS: Readonly<Record<string, number>> = {
  '4КІ1': 20,
  '4КІ2': 20,
  '4КІ3': 20,
  '2КІ3': 16,
};
