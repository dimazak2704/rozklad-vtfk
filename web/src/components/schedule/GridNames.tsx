import { useMemo, type ReactNode } from 'react';
import type { DayDto } from '@/api/types';
import { ambiguousGridNames } from '@/lib/teacher-name';
import { GridNamesContext } from './grid-names';

/**
 * Однофамільці на екрані: клітинки пишуть викладача прізвищем, а двох «Овчар» у тому самому розкладі —
 * з ініціалами («Овчар Ін.М.», «Овчар Ів.М.»). Рахуємо по всіх парах, які показує сітка, з оригіналами замін.
 */
export function GridNamesProvider({ days, children }: { days: readonly DayDto[] | undefined; children: ReactNode }) {
  const ambiguous = useMemo(
    () => ambiguousGridNames((days ?? []).flatMap((day) => day.lessons.flatMap((lesson) => [lesson.teacher, lesson.original?.teacher]))),
    [days],
  );
  return <GridNamesContext value={ambiguous}>{children}</GridNamesContext>;
}

/** Те саме, коли однофамільців уже пораховано (дошка розкладу в адмінці рахує по парах шаблону). */
export function GridNamesScope({ ambiguous, children }: { ambiguous: ReadonlySet<string>; children: ReactNode }) {
  return <GridNamesContext value={ambiguous}>{children}</GridNamesContext>;
}
