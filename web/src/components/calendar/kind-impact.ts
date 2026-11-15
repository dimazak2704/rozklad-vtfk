import type { PeriodKindImpactDto } from '@/api/types';

const SHOWN_CONFLICTS = 3;

/** Чому зміну виду не можна зберегти: тексти відмов сервера, потім живі заміни, з якими вона розійдеться. */
export function impactProblems(impact: PeriodKindImpactDto): string {
  const conflicts = impact.conflicts.slice(0, SHOWN_CONFLICTS).map((c) => c.message);
  const more = impact.conflicts.length - conflicts.length;
  const lead = impact.conflicts.length > 0 ? 'Зміна розійдеться з живими замінами — спершу змініть їх.' : null;
  return [...impact.blockers, lead, ...conflicts, more > 0 ? `І ще ${more}.` : null].filter(Boolean).join(' ');
}
