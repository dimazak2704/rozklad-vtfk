import { Check, TriangleExclamationPoint } from 'lucide-react';
import type { ImportReportRow, ProgramDraft } from '@/api/import';
import { Badge } from '@/components/ui';
import { cn } from '@/lib/cn';

const SOURCE_LABEL: Record<ProgramDraft['source'], string> = {
  TABLE: 'з таблиці структури',
  PDF_TEXT: 'з тексту PDF',
  AI: 'розпізнано ШІ (таблиці не знайдено)',
};

interface ProgramPreviewProps {
  draft: ProgramDraft;
  /** Рядки звіту про години (суми, тематичний план, тижні семестру). */
  checks: readonly ImportReportRow[];
}

/**
 * Години програми дисципліни: таблиця структури як у документі й перевірки сум. Числа — як написано
 * в програмі, нічого не «виправляємо»: розбіжності показуємо попередженнями.
 */
export function ProgramPreview({ draft, checks }: ProgramPreviewProps) {
  const hours = draft.hours;
  const rows: Array<{ label: string; value: number | null | undefined; strong?: boolean }> = hours
    ? [
        { label: 'Усього годин', value: hours.total, strong: true },
        { label: 'На заняттях', value: hours.classroom, strong: true },
        { label: 'Самостійна робота', value: hours.selfStudy },
        { label: 'Лекції', value: hours.lectures },
        { label: 'Практичні', value: hours.practice },
        { label: 'Лабораторні', value: hours.labs },
        { label: 'Семінарські', value: hours.seminars },
        { label: 'Консультації', value: hours.consultations },
      ]
    : [];
  const control = hours ? [hours.exam ? 'іспит' : null, hours.credit ? 'залік' : null].filter(Boolean).join(', ') : '';

  return (
    <div className="grid gap-5 lg:grid-cols-[minmax(0,22rem)_minmax(0,1fr)]">
      <div className="flex flex-col gap-3">
        <div className="flex flex-col gap-0.5">
          <p className="text-heading font-semibold text-primary">{draft.disciplineName ?? 'Назву не знайдено'}</p>
          <p className="text-caption text-muted">
            Години {SOURCE_LABEL[draft.source]}
            {draft.location ? ` · ${draft.location}` : ''}
          </p>
        </div>
        {hours ? (
          <table className="w-full border-separate border-spacing-0 overflow-hidden rounded-panel border border-default bg-surface text-ui">
            <caption className="sr-only">Структура навчальної дисципліни</caption>
            <tbody>
              {rows.map((row) => (
                <tr key={row.label}>
                  <th scope="row" className={cn('border-b border-separator px-3 py-1.5 text-start font-normal text-secondary', row.strong && 'font-semibold text-primary')}>
                    {row.label}
                  </th>
                  <td className={cn('border-b border-separator px-3 py-1.5 text-end figures', row.strong && 'font-semibold')}>
                    {row.value ?? <span className="text-muted">—</span>}
                  </td>
                </tr>
              ))}
              <tr>
                <th scope="row" className="px-3 py-1.5 text-start font-normal text-secondary">
                  Контроль
                </th>
                <td className="px-3 py-1.5 text-end">{control || <span className="text-muted">не вказано</span>}</td>
              </tr>
            </tbody>
          </table>
        ) : (
          <p className="text-ui text-secondary">Таблицю годин не знайдено.</p>
        )}
        <dl className="flex flex-wrap gap-x-6 gap-y-1 text-caption">
          <div className="flex gap-1">
            <dt className="text-muted">Годин на тиждень:</dt>
            <dd className="font-semibold text-primary figures">{draft.hoursPerWeek ?? '—'}</dd>
          </div>
          <div className="flex gap-1">
            <dt className="text-muted">Кредитів:</dt>
            <dd className="font-semibold text-primary figures">{draft.credits ?? '—'}</dd>
          </div>
          <div className="flex gap-1">
            <dt className="text-muted">Семестр у програмі:</dt>
            <dd className="font-semibold text-primary figures">{draft.semester ?? '—'}</dd>
          </div>
        </dl>
      </div>
      <section aria-label="Перевірки годин" className="flex flex-col gap-2">
        <h3 className="text-ui font-semibold text-primary">Перевірки</h3>
        {checks.length === 0 ? (
          <p className="text-caption text-secondary">Перевірок немає.</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {checks.map((check, index) => {
              const warn = check.severity === 'WARN' || check.severity === 'ERROR';
              return (
                <li
                  key={index}
                  className={cn(
                    'flex items-start gap-2.5 rounded-control border px-3 py-2',
                    warn ? 'border-change bg-change-subtle' : 'border-default bg-surface',
                  )}
                >
                  {warn ? (
                    <TriangleExclamationPoint aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-change" />
                  ) : (
                    <Check aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-add" />
                  )}
                  <span className="flex min-w-0 flex-col gap-0.5">
                    <span className="flex flex-wrap items-center gap-2">
                      <span className="font-condensed text-ui font-bold figures text-primary">{check.raw}</span>
                      {warn ? (
                        <Badge tone="warning" size="sm">
                          Увага
                        </Badge>
                      ) : null}
                    </span>
                    {check.message ? <span className="text-caption text-secondary">{check.message}</span> : null}
                  </span>
                </li>
              );
            })}
          </ul>
        )}
      </section>
    </div>
  );
}
