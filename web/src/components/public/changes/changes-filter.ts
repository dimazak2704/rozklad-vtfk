/**
 * Фільтр сторінки «Заміни»: група (нечітко: «4кі» → 4КІ1–4КІ3), курси, «лише мої» з обраного.
 * Викладачі в рядках розпорядження — текстом («Крисак Л.»), тож своїх шукаємо за прізвищем та ініціалами.
 */
import type { ChangeView, PublicChangesDto } from '@/api/types';
import { matchScore, normalizeText } from '@/lib/search';
import type { ScheduleTarget } from '@/stores/favorites';

export type ChangeGroup = PublicChangesDto['groups'][number];

export interface ChangesFilter {
  query: string;
  courses: readonly number[];
  mineOnly: boolean;
}

export interface MyTargets {
  groupIds: ReadonlySet<string>;
  /** Підписи обраних викладачів: «Кренцін», «Крисак Л.В.». */
  teachers: readonly string[];
}

export function myTargets(favorites: readonly ScheduleTarget[]): MyTargets {
  return {
    groupIds: new Set(favorites.filter((f) => f.type === 'GROUP').map((f) => f.id)),
    teachers: favorites.filter((f) => f.type === 'TEACHER').map((f) => f.label),
  };
}

function nameParts(name: string): { surname: string; initials: string } {
  const [surname = '', ...rest] = normalizeText(name).replace(/\./g, ' ').split(/\s+/).filter(Boolean);
  return { surname, initials: rest.map((part) => part.charAt(0)).join('') };
}

/**
 * «Крисак Л.» і «Крисак Л.В.» — та сама людина, «Крисак Л.» і «Крисак А.О.» — ні (DOMAIN §10:
 * однофамільців розрізняють ініціалами). Без ініціалів з одного боку — збіг за прізвищем.
 */
export function sameTeacher(a: string | null | undefined, b: string | null | undefined): boolean {
  if (!a || !b) return false;
  const x = nameParts(a);
  const y = nameParts(b);
  if (!x.surname || x.surname !== y.surname) return false;
  const shorter = x.initials.length <= y.initials.length ? x.initials : y.initials;
  const longer = shorter === x.initials ? y.initials : x.initials;
  return longer.startsWith(shorter);
}

function rowMentions(item: ChangeView, teachers: readonly string[]): boolean {
  return teachers.some((teacher) => sameTeacher(item.from.teacher, teacher) || sameTeacher(item.to.teacher, teacher));
}

/** Групи з рядками, що проходять фільтр; для «лише моїх» з чужої групи лишаються рядки з моїми викладачами. */
export function filterChangeGroups(groups: readonly ChangeGroup[], filter: ChangesFilter, mine: MyTargets): ChangeGroup[] {
  const query = filter.query.trim();
  return groups
    .filter((entry) => filter.courses.length === 0 || filter.courses.includes(entry.group.course))
    .filter((entry) => !query || matchScore(query, entry.group.code) >= 60)
    .map((entry) => {
      if (!filter.mineOnly || mine.groupIds.has(entry.group.id)) return entry;
      return { ...entry, items: entry.items.filter((item) => rowMentions(item, mine.teachers)) };
    })
    .filter((entry) => entry.items.length > 0);
}

export function hasMyTargets(mine: MyTargets): boolean {
  return mine.groupIds.size > 0 || mine.teachers.length > 0;
}

/** Курси, що трапляються в замінах дня (для фільтрів «1 курс»…). */
export function coursesIn(groups: readonly ChangeGroup[]): number[] {
  return [...new Set(groups.map((g) => g.group.course))].sort((a, b) => a - b);
}

export function parseCourses(raw: string | null): number[] {
  if (!raw) return [];
  return [...new Set(raw.split(',').map(Number).filter((n) => Number.isInteger(n) && n >= 1 && n <= 6))].sort((a, b) => a - b);
}
