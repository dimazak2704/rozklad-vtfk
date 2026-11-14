import { useMemo, type ReactNode } from 'react';
import type { DayDto, TeacherRef } from '@/api/types';
import { useTeacherNameDirectory } from '@/hooks/useTeacherNames';
import { ambiguousGridNames, type TeacherNameOptions } from '@/lib/teacher-name';
import { GridNamesContext } from './grid-names';

function useGridNameOptions(teachers: ReadonlyArray<TeacherRef | null | undefined>, scope: 'public' | 'admin'): TeacherNameOptions {
  const names = useTeacherNameDirectory(scope);
  return useMemo(() => ({ names, ambiguous: ambiguousGridNames(teachers, names) }), [teachers, names]);
}

/**
 * Клітинки пишуть викладача «Прізвище І. Б.»: ініціали з короткої форми, а коли їх там немає — з довідника імен
 * (публічного, з налаштуванням повних імен). Двох з однаковим підписом у тому самому розкладі — з іменем.
 * Рахуємо по всіх парах, які показує сітка, з оригіналами замін.
 */
export function GridNamesProvider({ days, children }: { days: readonly DayDto[] | undefined; children: ReactNode }) {
  const teachers = useMemo(() => (days ?? []).flatMap((day) => day.lessons.flatMap((lesson) => [lesson.teacher, lesson.original?.teacher])), [days]);
  return <GridNamesContext value={useGridNameOptions(teachers, 'public')}>{children}</GridNamesContext>;
}

/** Те саме для дошки розкладу в адмінці: викладачі пар шаблону, імена — з каталогу. */
export function GridNamesScope({ teachers, children }: { teachers: ReadonlyArray<TeacherRef | null | undefined>; children: ReactNode }) {
  return <GridNamesContext value={useGridNameOptions(teachers, 'admin')}>{children}</GridNamesContext>;
}
