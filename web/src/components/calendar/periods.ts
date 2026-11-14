import type { CalendarPeriod, CalendarPeriodRequest } from '@/api/calendar';
import type { TeacherRef } from '@/api/types';
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

/** Період → тіло PUT (для повернення попередніх значень із журналу дій). */
export function periodRequest(period: CalendarPeriod): CalendarPeriodRequest {
  return {
    semesterId: period.semesterId,
    kind: period.kind,
    title: period.title,
    startsOn: period.startsOn,
    endsOn: period.endsOn,
    studyMode: period.studyMode,
    orderNote: period.orderNote,
    scope: period.scope,
    courses: period.courses,
    groupIds: period.groupIds ?? period.groups.map((g) => g.id),
    supervisorIds: period.kind === 'PRACTICE' ? (period.supervisorIds ?? []) : [],
    version: period.version,
  };
}

/** «Керівник: Кренцін М.Д.» або «Керівники: Кренцін М.Д., Непийвода М.В.», як у паперах коледжу. */
export function supervisorsLabel(supervisors: ReadonlyArray<Pick<TeacherRef, 'shortName'>>): string {
  const names = supervisors.map((t) => t.shortName).join(', ');
  return `${supervisors.length === 1 ? 'Керівник' : 'Керівники'}: ${names}`;
}
