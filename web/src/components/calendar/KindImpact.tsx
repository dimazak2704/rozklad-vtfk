import type { PeriodEffect, PeriodKindImpactDto } from '@/api/types';
import { formatDateRange } from '@/lib/date';
import { countLabel } from '@/lib/labels';
import { EFFECT_LABEL } from './labels';

const SHOWN_GROUPS = 8;

/** Дати періодів одним рядком: «16–27 листопада», а для кількох проміжків — від першого до останнього дня. */
function datesSentence(dates: readonly string[]): string {
  const first = dates[0];
  const last = dates.at(-1);
  if (!first || !last) return 'дат у межах семестрів немає';
  return `${formatDateRange(first, last)} (${countLabel(dates.length, ['навчальний день', 'навчальні дні', 'навчальних днів'])})`;
}

/** Пари: «зникне 40 пар», «повернеться 12 пар», «зміниться 3». */
function lessonsSentence({ added, removed, updated }: PeriodKindImpactDto['lessons']): string {
  const parts = [
    removed > 0 ? `зникне ${countLabel(removed, ['пара', 'пари', 'пар'])}` : null,
    added > 0 ? `повернеться ${countLabel(added, ['пара', 'пари', 'пар'])}` : null,
    updated > 0 ? `зміниться ${countLabel(updated, ['пара', 'пари', 'пар'])}` : null,
  ].filter(Boolean);
  return parts.length > 0 ? `Від сьогодні ${parts.join(', ')}.` : 'Пари від сьогодні не зміняться.';
}

/** Що зачепить зміна ефекту: опис для діалогу підтвердження (дані — з пробного збереження сервера). */
export function KindImpactSummary({ impact, from }: { impact: PeriodKindImpactDto; from: PeriodEffect }) {
  const codes = impact.groups.map((g) => g.code);
  const shown = codes.slice(0, SHOWN_GROUPS).join(', ');
  const rest = codes.length - SHOWN_GROUPS;
  return (
    <span className="flex flex-col gap-2">
      <span>
        «{EFFECT_LABEL[from]}» → «{EFFECT_LABEL[impact.kind.effect]}» для {countLabel(impact.periods, ['періоду', 'періодів', 'періодів'])}
        : {datesSentence(impact.dates)}.
      </span>
      {codes.length > 0 ? (
        <span>
          Групи: {shown}
          {rest > 0 ? ` та ще ${rest}` : ''}.
        </span>
      ) : null}
      <span className="font-semibold text-primary">{lessonsSentence(impact.lessons)}</span>
      <span>Сайт, бот і розпорядження покажуть період за новим ефектом одразу після збереження.</span>
    </span>
  );
}
