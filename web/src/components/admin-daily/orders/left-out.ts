import type { DashboardDto } from '@/api/types';
import { countLabel, PAIR_FORMS } from '@/lib/labels';

const CHANGES = ['заміна', 'заміни', 'замін'] as const;

/** Що з дати не потрапить у чернетку розпорядження: три лічильники, що не перетинаються (панель core на дату). */
export interface LeftOut {
  /** Пари відсутніх викладачів, про які ще нічого не вирішено. */
  undecided: number;
  /** Зміни, що чекають відповіді викладача. */
  pending: number;
  /** Відмови, на які ще не обрано іншого кандидата. */
  declined: number;
}

export const NOTHING_LEFT_OUT: LeftOut = { undecided: 0, pending: 0, declined: 0 };

export function leftOutOf(dashboard: Pick<DashboardDto, 'undecidedLessons' | 'pending' | 'declined'> | undefined): LeftOut {
  if (!dashboard) return NOTHING_LEFT_OUT;
  return { undecided: dashboard.undecidedLessons ?? 0, pending: dashboard.pending, declined: dashboard.declined };
}

/** Пари без рішення чи відмови: групи можуть лишитися без викладача, це вже не просто «чекаємо». */
export function leftOutIsGap(left: LeftOut): boolean {
  return left.undecided > 0 || left.declined > 0;
}

/**
 * «3 пари без рішення, 1 заміна чекає підтвердження і 1 без нового кандидата після відмови»; null — нічого не лишилось.
 * Кінець речення («— у документ не потраплять») додає місце показу.
 */
export function leftOutText(left: LeftOut): string | null {
  const parts: string[] = [];
  if (left.undecided > 0) parts.push(`${countLabel(left.undecided, PAIR_FORMS)} без рішення`);
  if (left.pending > 0) parts.push(`${countLabel(left.pending, CHANGES)} ще ${left.pending === 1 ? 'чекає' : 'чекають'} підтвердження`);
  if (left.declined > 0) parts.push(`${left.declined} без нового кандидата після відмови`);
  if (parts.length === 0) return null;
  const last = parts.pop() as string;
  return parts.length > 0 ? `${parts.join(', ')} і ${last}` : last;
}
