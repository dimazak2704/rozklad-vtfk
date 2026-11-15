import type { CalendarPeriod, CalendarPeriodRequest } from '@/api/calendar';
import type { Nullable, PeriodEffect, TeacherRef, Uuid } from '@/api/types';
import { LEGACY_KIND, SCOPE_LABEL, STUDY_MODE_LABEL } from './labels';

/** Вид періоду для показу: з `periodKind`, а від старішого core — вбудований вид за старим `kind`. */
export interface ShownKind {
  id: Uuid | null;
  code: string;
  name: string;
  color: Nullable<string>;
  effect: PeriodEffect;
}

export function periodKindOf(period: Pick<CalendarPeriod, 'kind' | 'periodKind'>): ShownKind {
  if (period.periodKind) return period.periodKind;
  return { id: null, color: null, ...LEGACY_KIND[period.kind] };
}

/** Вид словом у таблиці: для форми навчання вистачає самої форми («Дистанційно»). */
export function periodKindLabel(period: Pick<CalendarPeriod, 'kind' | 'periodKind' | 'studyMode'>): string {
  const kind = periodKindOf(period);
  return kind.effect === 'ONLINE' && period.studyMode ? STUDY_MODE_LABEL[period.studyMode] : kind.name;
}

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

/**
 * Підпис смуги періоду на шкалі семестру: кого стосується й код виду — «4КІ1 · НС», «3 курс · НП». Смуги однакових
 * видів різняться саме групою; повна назва — у підказці.
 */
export function periodChipLabel(period: Pick<CalendarPeriod, 'scope' | 'courses' | 'groups' | 'kind' | 'periodKind'>): string {
  return `${periodAudience(period)} · ${periodKindOf(period).code}`;
}

/**
 * Період → тіло PUT (для повернення попередніх значень із журналу дій). Вид — `kindId`; лише від старішого
 * core без довідника видів — старий `kind`.
 */
export function periodRequest(period: CalendarPeriod): CalendarPeriodRequest {
  const suspend = periodKindOf(period).effect === 'SUSPEND';
  return {
    semesterId: period.semesterId,
    ...(period.kindId ? { kindId: period.kindId } : { kind: period.kind }),
    title: period.title,
    startsOn: period.startsOn,
    endsOn: period.endsOn,
    studyMode: period.studyMode,
    orderNote: period.orderNote,
    scope: period.scope,
    courses: period.courses,
    groupIds: period.groupIds ?? period.groups.map((g) => g.id),
    roomId: suspend ? (period.roomId ?? null) : null,
    supervisorIds: suspend ? (period.supervisorIds ?? []) : [],
    version: period.version,
  };
}

/** «Керівник: Кренцін М.Д.» або «Керівники: Кренцін М.Д., Непийвода М.В.», як у паперах коледжу. */
export function supervisorsLabel(supervisors: ReadonlyArray<Pick<TeacherRef, 'shortName'>>): string {
  const names = supervisors.map((t) => t.shortName).join(', ');
  return `${supervisors.length === 1 ? 'Керівник' : 'Керівники'}: ${names}`;
}
