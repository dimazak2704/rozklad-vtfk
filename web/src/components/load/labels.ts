import type { ControlForm, LessonKind, LoadSource } from '@/api/types';
import type { BadgeTone, SelectOption } from '@/components/ui';
import { capitalize, LESSON_KIND_LABEL } from '@/lib/labels';

export const LESSON_KIND_OPTIONS: ReadonlyArray<SelectOption<LessonKind>> = (['MIXED', 'LECTURE', 'PRACTICE', 'LAB', 'SEMINAR'] as const).map(
  (kind) => ({
    value: kind,
    label: capitalize(LESSON_KIND_LABEL[kind]),
    description: kind === 'MIXED' ? 'Вид не розділяють (так у таблиці розкладу)' : undefined,
  }),
);

export const CONTROL_FORM_LABEL: Record<ControlForm, string> = {
  EXAM: 'Іспит',
  CREDIT: 'Залік',
  NONE: 'Немає',
};

export const LOAD_SOURCE_LABEL: Record<LoadSource, string> = {
  MANUAL: 'Вручну',
  CURRICULUM: 'Навчальний план',
  PROGRAM: 'Програма дисципліни',
  TIMETABLE_DERIVED: 'З розкладу',
};

export const LOAD_SOURCE_HINT: Record<LoadSource, string> = {
  MANUAL: 'Внесено вручну — повторне виведення з розкладу його не перезапише.',
  CURRICULUM: 'З навчального плану.',
  PROGRAM: 'З програми навчальної дисципліни.',
  TIMETABLE_DERIVED: 'Виведено з опублікованого розкладу: пара щотижня — 1, лише чисельник чи знаменник — ½.',
};

export const LOAD_SOURCE_TONE: Record<LoadSource, BadgeTone> = {
  MANUAL: 'neutral',
  CURRICULUM: 'accent',
  PROGRAM: 'accent',
  TIMETABLE_DERIVED: 'neutral',
};
