import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { useNextOrderNumber } from '@/api/admin-daily';
import { useAiUsage } from '@/api/import';
import type { IsoDate } from '@/api/types';
import { SkeletonText } from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatNumericDate } from '@/lib/date';
import { countLabel } from '@/lib/labels';
import { usd } from '../format';

const CALLS = ['виклик', 'виклики', 'викликів'] as const;

function Item({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-caption text-muted">{term}</dt>
      <dd className="text-ui text-primary">{children}</dd>
    </div>
  );
}

/** Нумерація розпоряджень: останній опублікований номер семестру і який буде наступним. */
export function NumberingInfo({ semester }: { semester: { startsOn: IsoDate; endsOn: IsoDate } | undefined }) {
  const next = useNextOrderNumber(semester);
  const last = next.data?.items[0];
  return (
    <dl className="grid gap-4 rounded-control border border-separator bg-sunken px-4 py-3 sm:grid-cols-2">
      <Item term="Останній у семестрі">
        {next.isPending ? (
          <SkeletonText lines={1} />
        ) : last ? (
          <Link to={`/admin/orders?date=${last.date}`} className="font-semibold text-accent underline-offset-4 hover:underline">
            №{last.number} від {formatNumericDate(last.date)}
          </Link>
        ) : (
          'Ще не публікували'
        )}
      </Item>
      <Item term="Наступний отримає">
        {next.nextNumber !== null ? <span className="font-condensed text-heading font-bold figures">№{next.nextNumber}</span> : '—'}
      </Item>
    </dl>
  );
}

/** ШІ: які моделі працюють, денний бюджет і скільки вже витрачено. Змінюються змінними середовища. */
export function AiInfo() {
  const usage = useAiUsage();
  if (usage.isPending) return <SkeletonText lines={3} />;
  if (usage.isError) {
    return <p className="text-ui text-secondary">Сервіс імпорту зараз не відповідає — витрати й моделі невідомі. Імпорт без ШІ працює.</p>;
  }
  const { today, dailyBudgetUsd, model } = usage.data;
  const share = dailyBudgetUsd > 0 ? Math.min(100, (today.costUsd / dailyBudgetUsd) * 100) : 0;
  return (
    <div className="flex flex-col gap-4">
      <dl className="grid gap-4 sm:grid-cols-2">
        <Item term="Модель для тексту">
          <code className="font-mono text-caption">{model.text}</code>
        </Item>
        <Item term="Модель для фото й сканів">
          <code className="font-mono text-caption">{model.vision}</code>
        </Item>
        <Item term="Витрачено сьогодні">
          <span className="figures">
            {usd(today.costUsd)} з {usd(dailyBudgetUsd)} · {countLabel(today.calls, CALLS)}
          </span>
        </Item>
      </dl>
      <div
        role="meter"
        aria-label="Використано денного бюджету ШІ"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(share)}
        aria-valuetext={`${Math.round(share)}% бюджету`}
        className="h-2 max-w-md overflow-hidden rounded-full bg-sunken"
      >
        <span className={cn('block h-full rounded-full', share >= 90 ? 'bg-danger' : 'bg-accent')} style={{ width: `${Math.max(share, today.costUsd > 0 ? 1 : 0)}%` }} />
      </div>
      <p className="text-caption text-muted">
        Модель і бюджет задаються змінними середовища сервісу імпорту: <code className="font-mono">AI_MODEL_TEXT</code>,{' '}
        <code className="font-mono">AI_MODEL_VISION</code>, <code className="font-mono">AI_DAILY_BUDGET_USD</code>. Порожній ключ API вимикає ШІ.
      </p>
    </div>
  );
}
