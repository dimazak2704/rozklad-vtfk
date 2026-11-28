import type { DashboardDto } from '@/api/types';
import { PAIR_FORMS, plural } from '@/lib/labels';

/** Скільки роботи чекає на наступний навчальний день: пари без заміни й неопубліковане розпорядження. */
export function nextDayForward(
  summary: Pick<DashboardDto, 'uncoveredLessons' | 'absences' | 'orderDraft'>,
  dayName: { isTomorrow: boolean; weekday: string },
): { text: string; needsAttention: boolean } {
  const onDay = dayName.isTomorrow ? 'На завтра' : `На ${dayName.weekday}`;
  const open = summary.uncoveredLessons;
  const draft = (summary.orderDraft?.itemCount ?? 0) > 0;
  const unpublished = draft ? ', розпорядження ще не опубліковане' : '';
  if (open > 0) {
    const head = dayName.isTomorrow ? 'Завтра' : onDay;
    return { text: `${head} ${open} ${plural(open, PAIR_FORMS)} без заміни${unpublished}.`, needsAttention: true };
  }
  if (summary.absences > 0) return { text: `${onDay} усі пари вже вирішено${unpublished}.`, needsAttention: draft };
  // без відсутностей розпорядження все одно буває: додаткова пара, інша аудиторія
  if (draft) return { text: `${onDay} розпорядження ще не опубліковане.`, needsAttention: true };
  return { text: `${onDay} відсутностей поки немає.`, needsAttention: false };
}
