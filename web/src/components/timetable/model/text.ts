/** Тексти про пари шаблону: що писати в клітинці, підпис для читача екрана, короткі назви слотів. */
import type { TimetableConflict } from '@/api/timetable';
import type { MoveLessonRequest, TemplateLessonDto } from '@/api/types';
import { capitalize, weekdayName, weekdayShort } from '@/lib/labels';
import { roomLabel } from '@/lib/schedule';
import type { BoardView } from './grid';
import { PARITY_SHORT } from './labels';

/** Що писати в клітинці залежно від подання: групі — викладача, викладачу — групу, аудиторії — обох. */
export function chipText(lesson: TemplateLessonDto, view: BoardView): { title: string; detail: string } {
  const room = roomLabel(lesson.room?.name);
  const cohort = lesson.subgroup > 0 ? `${lesson.group.code}/${lesson.subgroup}` : lesson.group.code;
  if (view === 'group') {
    return { title: lesson.discipline.shortName, detail: [lesson.teacher?.displayName, room].filter(Boolean).join(' · ') };
  }
  if (view === 'teacher') return { title: cohort, detail: [lesson.discipline.shortName, room].filter(Boolean).join(' · ') };
  return { title: cohort, detail: [lesson.discipline.shortName, lesson.teacher?.displayName].filter(Boolean).join(' · ') };
}

/** Усе, що видно в клітинці (і конфлікти), одним рядком для читача екрана. */
export function lessonAriaLabel(lesson: TemplateLessonDto, conflicts: readonly TimetableConflict[]): string {
  const parts = [
    lesson.discipline.name || lesson.discipline.shortName,
    lesson.subgroup > 0 ? `${lesson.group.code}, ${lesson.subgroup} підгрупа` : lesson.group.code,
    lesson.teacher?.shortName,
    lesson.room ? `аудиторія ${lesson.room.name}` : null,
    `${weekdayName(lesson.weekday)}, ${lesson.pair} пара, ${PARITY_SHORT[lesson.parity]}`,
  ];
  const hard = conflicts.filter((c) => c.severity === 'HARD');
  if (hard.length > 0) parts.push(`конфлікт: ${hard.map((c) => c.message).join('; ')}`);
  if (lesson.pinned) parts.push('закріплена');
  return parts.filter(Boolean).join(', ');
}

/** «Середа, 4 пара, чисельник» */
export function slotLong(slot: Pick<MoveLessonRequest, 'weekday' | 'pair' | 'parity'>): string {
  return `${capitalize(weekdayName(slot.weekday))}, ${slot.pair} пара, ${PARITY_SHORT[slot.parity]}`;
}

/** «ср 4 (ч)» — для тостів і історії. */
export function slotShort(slot: Pick<MoveLessonRequest, 'weekday' | 'pair' | 'parity'>): string {
  const parity = slot.parity === 'BOTH' ? '' : slot.parity === 'NUMERATOR' ? ' (ч)' : ' (з)';
  return `${weekdayShort(slot.weekday).toLocaleLowerCase('uk')} ${slot.pair}${parity}`;
}

/** «СТП · 4КІ3/1» */
export function lessonShort(lesson: Pick<TemplateLessonDto, 'discipline' | 'group' | 'subgroup'>): string {
  return `${lesson.discipline.shortName} · ${lesson.group.code}${lesson.subgroup > 0 ? `/${lesson.subgroup}` : ''}`;
}

/** Повна назва пари для підтверджень: «СТП · 4КІ3 — вівторок, 4 пара, щотижня». */
export function lessonTitle(lesson: TemplateLessonDto): string {
  return `${lessonShort(lesson)} — ${weekdayName(lesson.weekday)}, ${lesson.pair} пара, ${PARITY_SHORT[lesson.parity]}`;
}
