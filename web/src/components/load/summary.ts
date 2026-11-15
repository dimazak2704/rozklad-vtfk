import type { LoadItemDto, TeacherRef } from '@/api/types';

export interface TeacherLoadSummary {
  teacher: TeacherRef;
  /** Години на тиждень, які веде саме цей викладач (по одній на кожну свою підгрупу рядка). */
  hours: number;
  planned: number;
  rows: number;
  groups: number;
}

/**
 * Усі рядки — одного викладача (пошук за прізвищем): його тиждень і семестр одним реченням, як у групи. null — у рядках
 * кілька викладачів, рядків немає або в якомусь рядку його немає.
 */
export function teacherLoadSummary(items: ReadonlyArray<Pick<LoadItemDto, 'teachers' | 'hoursPerWeek' | 'plannedHours' | 'group'>>): TeacherLoadSummary | null {
  const first = items[0];
  if (!first) return null;
  const candidates = new Map(first.teachers.map((t) => [t.teacher.id, t.teacher]));
  for (const item of items) {
    const ids = new Set(item.teachers.map((t) => t.teacher.id));
    for (const id of [...candidates.keys()]) if (!ids.has(id)) candidates.delete(id);
  }
  // кілька спільних викладачів (усі рядки ведуть двоє) — не вгадуємо, чий це тиждень
  if (candidates.size !== 1) return null;
  const teacher = [...candidates.values()][0] as TeacherRef;
  let hours = 0;
  let planned = 0;
  for (const item of items) {
    const own = item.teachers.filter((t) => t.teacher.id === teacher.id).length;
    hours += item.hoursPerWeek * own;
    planned += item.plannedHours * own;
  }
  return { teacher, hours, planned, rows: items.length, groups: new Set(items.map((item) => item.group.id)).size };
}
