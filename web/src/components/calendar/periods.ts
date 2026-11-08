import type { CalendarPeriod } from '@/api/calendar';
import { SCOPE_LABEL } from './labels';

/** Кого стосується період: «Усі групи», «3 курс», «2КІ3» або «4КІ1, 4КІ2, 4КІ3 та ще 2». */
export function periodAudience(period: Pick<CalendarPeriod, 'scope' | 'courses' | 'groups'>): string {
  if (period.scope === 'COURSES') {
    const courses = [...(period.courses ?? [])].sort((a, b) => a - b);
    return courses.length === 1 ? `${courses[0]} курс` : `${courses.join(', ')} курси`;
  }
  if (period.scope === 'GROUPS') {
    const codes = period.groups.map((g) => g.code);
    return codes.length > 3 ? `${codes.slice(0, 3).join(', ')} та ще ${codes.length - 3}` : codes.join(', ');
  }
  return SCOPE_LABEL.ALL;
}
