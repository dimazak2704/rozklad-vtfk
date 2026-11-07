import type { Weekday } from '@/api/types';

/** Рядок групи з таблиці: код, курс, домашня аудиторія, куратор (як у рядку кураторів). */
export type RawGroup = readonly [code: string, course: number, homeRoom: string | null, curator: string | null];

/** Парність рядка таблиці: B — обидва тижні, N — чисельник (верхній рядок), D — знаменник (нижній). */
export type RawParity = 'B' | 'N' | 'D';

/** Заняття базового розкладу: як у клітинках [дисципліна, викладач, аудиторія] аркуша «Розклад друк». */
export type RawLesson = readonly [
  group: string,
  weekday: Weekday,
  pair: number,
  parity: RawParity,
  subgroup: number,
  discipline: string,
  teacher: string | null,
  room: string | null,
];
