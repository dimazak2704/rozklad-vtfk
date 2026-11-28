import { z } from 'zod';
import type { CalendarPeriod } from '@/api/calendar';
import type { PeriodKindDto } from '@/api/types';
import { isIsoDate } from '@/lib/date';
import { LEGACY_KIND } from './labels';

export const periodSchema = z
  .object({
    kindId: z.string().min(1, 'Оберіть вид періоду'),
    title: z.string().trim().min(1, 'Назвіть період: «Осінні канікули», «Основи національного спротиву»').max(300, 'Не довше за 300 символів'),
    startsOn: z.string().refine(isIsoDate, 'Вкажіть дату початку'),
    endsOn: z.string().refine(isIsoDate, 'Вкажіть дату кінця'),
    studyMode: z.enum(['OFFLINE', 'ONLINE', 'MIXED']).nullable(),
    scope: z.enum(['ALL', 'COURSES', 'GROUPS']),
    courses: z.array(z.number()),
    groupIds: z.array(z.string()),
    roomId: z.string().nullable(),
    supervisorIds: z.array(z.string()),
    orderNote: z.string().trim().max(500, 'Не довше за 500 символів'),
  })
  .superRefine((value, ctx) => {
    if (isIsoDate(value.startsOn) && isIsoDate(value.endsOn) && value.endsOn < value.startsOn) {
      ctx.addIssue({ code: 'custom', path: ['endsOn'], message: 'Кінець не може бути раніше за початок' });
    }
    if (value.scope === 'COURSES' && value.courses.length === 0) {
      ctx.addIssue({ code: 'custom', path: ['courses'], message: 'Позначте хоча б один курс' });
    }
    if (value.scope === 'GROUPS' && value.groupIds.length === 0) {
      ctx.addIssue({ code: 'custom', path: ['groupIds'], message: 'Додайте хоча б одну групу' });
    }
  });

export type PeriodValues = z.infer<typeof periodSchema>;
export const PERIOD_FIELDS = ['kindId', 'title', 'startsOn', 'endsOn', 'studyMode', 'scope', 'courses', 'groupIds', 'roomId', 'supervisorIds', 'orderNote'] as const;
/** Вид нового періоду за замовчуванням — практика, як було до довідника видів. */
const DEFAULT_KIND_CODE = 'НП';

/** Вид періоду в довіднику: за `kindId`, а для періоду від старішого core — вбудований за старим `kind`. */
export function initialKindId(period: CalendarPeriod | null, kinds: readonly PeriodKindDto[]): string {
  if (period?.kindId) return period.kindId;
  const code = period ? LEGACY_KIND[period.kind].code : DEFAULT_KIND_CODE;
  return kinds.find((k) => k.code === code)?.id ?? kinds.find((k) => k.active)?.id ?? '';
}

/** Початкові значення форми: поля періоду, а для нового — порожні з охопленням «Групи». */
export function periodDefaults(period: CalendarPeriod | null, kinds: readonly PeriodKindDto[] | undefined): PeriodValues {
  return {
    // довідник уже в кеші (форму відкрили вдруге): вид одразу; інакше його підставить ефект у формі
    kindId: period?.kindId ?? (kinds ? initialKindId(period, kinds) : ''),
    title: period?.title ?? '',
    startsOn: period?.startsOn ?? '',
    endsOn: period?.endsOn ?? '',
    studyMode: period?.studyMode ?? null,
    scope: period?.scope ?? 'GROUPS',
    courses: period?.courses ?? [],
    groupIds: period?.groupIds ?? [],
    roomId: period?.roomId ?? null,
    supervisorIds: period?.supervisorIds ?? [],
    orderNote: period?.orderNote ?? '',
  };
}
