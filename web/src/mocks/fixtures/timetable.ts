/**
 * Опублікований базовий розклад мок-API: шаблонні заняття (TemplateLessonDto) з реальної таблиці
 * 2025/26, перенесені на I семестр 2026/2027 (DOMAIN §12).
 */
import type { Parity, TemplateLessonDto, Uuid } from '@/api/types';
import { mockId } from '../ids';
import { ACTIVE_SEMESTER } from './calendar';
import { disciplineNamed, disciplineRef, groupNamed, groupRef, roomNamed, roomRef, teacherNamed, teacherRef } from './catalog';
import { RAW_LESSONS } from './timetable.data';
import type { RawParity } from './timetable.types';

const PARITY: Record<RawParity, Parity> = { B: 'BOTH', N: 'NUMERATOR', D: 'DENOMINATOR' };

export const PUBLISHED_VERSION_ID: Uuid = mockId('timetable-version', 'excel-2025-26-semester-1');

/** Ключ потоку: один викладач веде ту саму дисципліну кільком групам в одній аудиторії одночасно. */
function streamKey(lesson: (typeof RAW_LESSONS)[number]): string | null {
  const [, weekday, pair, parity, , discipline, teacher, room] = lesson;
  return teacher ? `${weekday}|${pair}|${parity}|${teacher}|${discipline}|${room ?? ''}` : null;
}

const streamSizes = new Map<string, Set<string>>();
for (const lesson of RAW_LESSONS) {
  const key = streamKey(lesson);
  if (key) streamSizes.set(key, (streamSizes.get(key) ?? new Set<string>()).add(lesson[0]));
}

/** Заняття опублікованої версії. `lessonKind` у таблиці не вказано — MIXED (DOMAIN §3). */
export const TEMPLATE_LESSONS: readonly TemplateLessonDto[] = RAW_LESSONS.map((lesson) => {
  const [code, weekday, pair, parity, subgroup, discipline, teacher, room] = lesson;
  const key = streamKey(lesson);
  const isStream = key !== null && (streamSizes.get(key)?.size ?? 0) > 1;
  return {
    id: mockId('template-lesson', `${code}|${weekday}|${pair}|${parity}|${subgroup}`),
    weekday,
    pair,
    parity: PARITY[parity],
    group: groupRef(groupNamed(code)),
    subgroup,
    discipline: disciplineRef(disciplineNamed(discipline)),
    teacher: teacher ? teacherRef(teacherNamed(teacher)) : null,
    room: room ? roomRef(roomNamed(room)) : null,
    lessonKind: 'MIXED',
    loadItemId: null,
    pinned: false,
    online: false,
    streamId: isStream && key ? mockId('stream', key) : null,
  };
});

/**
 * Опублікована версія діє з початку семестру (DOMAIN §12). Конфлікти й метрики версії (TimetableVersionDto)
 * тут не рахуються — це робота області «Базовий розклад».
 */
export const PUBLISHED_VERSION_EFFECTIVE_FROM = ACTIVE_SEMESTER.startsOn;

/** Дисципліни, які викладач веде за розкладом (компетенція для ранжування замін, DOMAIN §7). */
export function disciplinesOfTeacher(teacherId: Uuid): Set<Uuid> {
  return new Set(TEMPLATE_LESSONS.filter((l) => l.teacher?.id === teacherId).map((l) => l.discipline.id));
}
