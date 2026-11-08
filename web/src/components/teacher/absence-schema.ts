import { z } from 'zod';
import type { AbsenceReasonCategory, IsoDate, MyAbsenceRequest } from '@/api/types';
import { compareIsoDates, isIsoDate } from '@/lib/date';

export const REASONS = ['SICK_LEAVE', 'VACATION', 'BUSINESS_TRIP', 'PERSONAL', 'OTHER'] as const satisfies readonly AbsenceReasonCategory[];
/** Межа core (`@Size(max = 1000)`). */
export const COMMENT_MAX = 1000;
export const ALL_PAIRS = [1, 2, 3, 4, 5, 6, 7, 8] as const;

/**
 * Форма відсутності. `today` — сьогодні за Києвом: відсутність, що вже закінчилась, повідомити не можна
 * (core: 422 `ABSENCE_IN_PAST`, коли dateTo раніше за сьогодні); ту, що вже триває, — можна.
 */
export function absenceSchema(today: IsoDate) {
  return z
    .object({
      dateFrom: z.string().refine(isIsoDate, 'Оберіть дату початку'),
      dateTo: z.string().refine(isIsoDate, 'Оберіть дату завершення'),
      wholeDay: z.boolean(),
      pairs: z.array(z.number().int().min(1).max(8)),
      reasonCategory: z.enum(REASONS, { error: 'Оберіть причину' }),
      comment: z.string().max(COMMENT_MAX, `Не більше ${COMMENT_MAX} символів`),
    })
    .superRefine((values, ctx) => {
      if (isIsoDate(values.dateTo) && compareIsoDates(values.dateTo, today) < 0) {
        ctx.addIssue({ code: 'custom', path: ['dateTo'], message: 'Ця відсутність уже минула — вкажіть поточну чи майбутню' });
      }
      if (isIsoDate(values.dateFrom) && isIsoDate(values.dateTo) && compareIsoDates(values.dateTo, values.dateFrom) < 0) {
        ctx.addIssue({ code: 'custom', path: ['dateTo'], message: 'Не раніше за дату початку' });
      }
      if (!values.wholeDay && values.pairs.length === 0) {
        ctx.addIssue({ code: 'custom', path: ['pairs'], message: 'Оберіть хоча б одну пару або «увесь день»' });
      }
    });
}

export type AbsenceFormValues = z.infer<ReturnType<typeof absenceSchema>>;

/** Значення форми → тіло POST /api/me/absences (null у pairs — увесь день). */
export function toAbsenceRequest(values: AbsenceFormValues): MyAbsenceRequest {
  return {
    dateFrom: values.dateFrom,
    dateTo: values.dateTo,
    pairs: values.wholeDay ? null : [...values.pairs].sort((a, b) => a - b),
    reasonCategory: values.reasonCategory,
    comment: values.comment.trim() ? values.comment.trim() : null,
  };
}
