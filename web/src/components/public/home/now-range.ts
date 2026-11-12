import type { IsoDate, PublicMetaDto } from '@/api/types';
import { compareIsoDates } from '@/lib/date';

/** Сьогодні й наступний навчальний день групи одним запитом (≤ 31 дня): так питає NowPanel, а головна — те саме. */
export function nowPanelRange(meta: Pick<PublicMetaDto, 'today' | 'nextStudyDay'> | undefined): { from: IsoDate; to: IsoDate } | null {
  if (!meta) return null;
  const nextDay = meta.nextStudyDay?.date ?? null;
  return { from: meta.today, to: nextDay && compareIsoDates(nextDay, meta.today) > 0 ? nextDay : meta.today };
}
