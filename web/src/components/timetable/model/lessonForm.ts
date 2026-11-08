/** Форма пари: схема перевірки (zod), порожні значення, перетворення з/у DTO. */
import { z } from 'zod';
import type { TemplateLessonDto, TemplateLessonRequest, Weekday } from '@/api/types';

export const lessonSchema = z.object({
  groupId: z.string().min(1, 'Оберіть групу'),
  subgroup: z.number().int().min(0).max(4),
  disciplineId: z.string().min(1, 'Оберіть дисципліну'),
  teacherId: z.string().nullable(),
  roomId: z.string().nullable(),
  weekday: z.number().int().min(1, 'Оберіть день').max(5, 'Оберіть день'),
  pair: z.number().int().min(1, 'Пара — від 1 до 8').max(8, 'Пара — від 1 до 8'),
  parity: z.enum(['BOTH', 'NUMERATOR', 'DENOMINATOR']),
  lessonKind: z.enum(['LECTURE', 'PRACTICE', 'LAB', 'SEMINAR', 'MIXED']),
  online: z.boolean(),
  pinned: z.boolean(),
});

export type LessonFormValues = z.infer<typeof lessonSchema>;

export const EMPTY_LESSON_FORM: LessonFormValues = {
  groupId: '',
  subgroup: 0,
  disciplineId: '',
  teacherId: null,
  roomId: null,
  weekday: 1,
  pair: 1,
  parity: 'BOTH',
  lessonKind: 'MIXED',
  online: false,
  pinned: false,
};

export const LESSON_FORM_FIELDS: ReadonlySet<string> = new Set(Object.keys(EMPTY_LESSON_FORM));

export function formValuesOf(lesson: TemplateLessonDto): LessonFormValues {
  return {
    groupId: lesson.group.id,
    subgroup: lesson.subgroup,
    disciplineId: lesson.discipline.id,
    teacherId: lesson.teacher?.id ?? null,
    roomId: lesson.room?.id ?? null,
    weekday: lesson.weekday,
    pair: lesson.pair,
    parity: lesson.parity,
    lessonKind: lesson.lessonKind,
    online: lesson.online,
    pinned: lesson.pinned,
  };
}

/** Значення форми → тіло POST/PUT; навантаження й потік редагованої пари зберігаються. */
export function requestOf(values: LessonFormValues, lesson: TemplateLessonDto | null): TemplateLessonRequest {
  return {
    weekday: values.weekday as Weekday,
    pair: values.pair,
    parity: values.parity,
    groupId: values.groupId,
    subgroup: values.subgroup,
    disciplineId: values.disciplineId,
    teacherId: values.teacherId,
    roomId: values.roomId,
    lessonKind: values.lessonKind,
    loadItemId: lesson?.loadItemId ?? null,
    pinned: values.pinned,
    online: values.online,
    streamId: lesson?.streamId ?? null,
  };
}
