/**
 * Тижні теорії групи й пояснення плану (рішення власника 15.11.2026): тижні, коли група не на теорії (НС, практика,
 * сесія, збори, канікули), не рахуються й не надолужуються. Розрахунок робить сервер (load/LoadPlan, calendar
 * StudyCalendar.theory) — тут лише слова.
 */
import type { GroupOffPeriodDto, GroupTheoryDto, PlanSource } from '@/api/types';
import { formatHours } from '@/components/load/pattern';
import { parseIsoDate } from '@/lib/date';
import { countLabel, plural } from '@/lib/labels';

const WEEK_FORMS = ['тиждень', 'тижні', 'тижнів'] as const;

/** Одне речення для сторінок обліку й навантаження. */
export const PLAN_RULE =
  'План дисципліни — її години на тиждень лише за тижні, коли група на теорії: тижні НС, практики, сесії, зборів і канікул не рахуються й не надолужуються.';

/** «30.11–11.12», один день — «28.12». */
export function shortRange(from: string, to: string): string {
  const day = (iso: string) => {
    const { month, day: d } = parseIsoDate(iso);
    return `${String(d).padStart(2, '0')}.${String(month).padStart(2, '0')}`;
  };
  return from === to ? day(from) : `${day(from)}–${day(to)}`;
}

/** «НС 30.11–11.12» — код виду, як у графіку освітнього процесу. */
export function offPeriodLabel(period: GroupOffPeriodDto): string {
  return `${period.kind.code} ${shortRange(period.startsOn, period.endsOn)}`;
}

/** Повна назва для підказки: «Національний спротив: Основи національного спротиву, 30.11–11.12». */
export function offPeriodTitle(period: GroupOffPeriodDto): string {
  const name = period.title.toLocaleLowerCase('uk') === period.kind.name.toLocaleLowerCase('uk') ? period.kind.name : `${period.kind.name}: ${period.title}`;
  return `${name}, ${shortRange(period.startsOn, period.endsOn)}`;
}

/** «16 тижнів теорії з 18» */
export function theoryWeeksLabel(theory: Pick<GroupTheoryDto, 'theoryWeeks' | 'semesterWeeks'>): string {
  return `${countLabel(theory.theoryWeeks, WEEK_FORMS)} теорії з ${theory.semesterWeeks}`;
}

/** «4КІ2: 16 тижнів теорії з 18 — НС 30.11–11.12, К 28.12–30.12» */
export function theoryLine(theory: GroupTheoryDto): string {
  const head = `${theory.group.code}: ${theoryWeeksLabel(theory)}`;
  return theory.offPeriods.length === 0 ? head : `${head} — ${theory.offPeriods.map(offPeriodLabel).join(', ')}`;
}

/** Графік освітнього процесу каже інше число — це лише звірка, план рахується за календарем системи. */
export function graphDiffers(theory: Pick<GroupTheoryDto, 'theoryWeeks' | 'graphTheoryWeeks'>): boolean {
  return theory.graphTheoryWeeks != null && theory.graphTheoryWeeks !== theory.theoryWeeks;
}

/** «У графіку освітнього процесу — 20 тижнів теорії: …» або null, коли числа збігаються чи графіка немає. */
export function graphWarning(theory: GroupTheoryDto): string | null {
  if (!graphDiffers(theory)) return null;
  const weeks = theory.graphTheoryWeeks as number;
  return `У графіку освітнього процесу — ${countLabel(weeks, WEEK_FORMS)} теорії: там інші межі семестру. План рахується за календарем системи.`;
}

/** Підказка до числа плану в рядку обліку чи навантаження. */
export function planHint(
  row: { planSource: PlanSource | 'MIXED'; hoursPerWeek: number },
  theory?: Pick<GroupTheoryDto, 'theoryWeeks' | 'semesterWeeks'> | null,
): string {
  if (row.planSource === 'FIXED') return 'План задано числом (вручну чи з програми дисципліни) — система його не змінює.';
  const weeks = theory ? ` (${theoryWeeksLabel(theory)})` : '';
  const auto = `${formatHours(row.hoursPerWeek)} год на тиждень лише за тижні теорії групи${weeks}, за парами розкладу.`;
  return row.planSource === 'MIXED' ? `Частину плану задано числом, решту рахує система: ${auto}` : `План рахує система: ${auto}`;
}

/** «13 груп розходяться з графіком» для згорнутого списку груп. */
export function graphMismatchCount(count: number): string {
  return `${count}\u00a0${plural(count, ['група розходиться', 'групи розходяться', 'груп розходяться'])} з графіком`;
}
