import { ArrowRight, CircleCheck, CircleX, Play } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { isSkipped, useApplyImport, type ImportApplyResult, type ImportJob } from '@/api/import';
import { Button, buttonClass, notify } from '@/components/ui';
import { cn } from '@/lib/cn';
import { countLabel } from '@/lib/labels';
import { confirm } from '@/stores/confirm';

interface ApplyPanelProps {
  job: ImportJob;
  onShowDecisions: () => void;
}

const APPLY_EFFECT: Record<ImportJob['kind'], string> = {
  TIMETABLE_XLSX: 'Буде створено нових записів каталогу й псевдонімів за вашими рішеннями та чернетку версії розкладу. Опублікований розклад не зміниться.',
  DISCIPLINE_PROGRAM: 'Години з програми запишуться в навантаження вибраних груп (замінять наявні для цієї дисципліни).',
  CURRICULUM: 'Години плану запишуться в навантаження вибраних груп за дисциплінами, що мають збіг.',
  ORDER_DOCUMENT: 'Рядки розпорядження запишуться як історичні зміни на його дату. Поточні заміни це не зачепить.',
};

/** Що записано в core: підсумок кроків застосування людськими словами (порожні кроки пропускаємо). */
function resultLines(result: ImportApplyResult): string[] {
  const lines: string[] = [];
  const catalog = result.catalog;
  if (catalog && !isSkipped(catalog)) {
    const parts = [
      catalog.created ? countLabel(catalog.created, ['новий запис', 'нові записи', 'нових записів']) : null,
      catalog.updated ? `оновлено: ${catalog.updated}` : null,
      catalog.unchanged ? `без змін: ${catalog.unchanged}` : null,
    ].filter(Boolean);
    if (parts.length > 0) lines.push(`Каталог: ${parts.join(', ')}.`);
    lines.push(...catalog.warnings);
  }
  const timetable = result.timetable;
  if (timetable) {
    const conflicts = timetable.hardConflicts
      ? `, ${countLabel(timetable.hardConflicts, ['конфлікт', 'конфлікти', 'конфліктів'])}`
      : '';
    lines.push(`Чернетка «${timetable.name}»: ${countLabel(timetable.lessonCount, ['заняття', 'заняття', 'занять'])}${conflicts}.`);
  }
  const load = result.load;
  if (load && !isSkipped(load)) {
    const parts = [
      load.created ? countLabel(load.created, ['новий рядок', 'нові рядки', 'нових рядків']) : null,
      load.updated ? `оновлено: ${load.updated}` : null,
      load.unchanged ? `без змін: ${load.unchanged}` : null,
    ].filter(Boolean);
    if (parts.length > 0) lines.push(`Навантаження: ${parts.join(', ')}.`);
  }
  return lines;
}

/** Секунди від моменту, коли почалося застосування (живий лічильник замість вигаданого відсотка). */
function useElapsed(running: boolean): number {
  const [elapsed, setElapsed] = useState(0);
  useEffect(() => {
    if (!running) return undefined;
    const startedAt = Date.now();
    const timer = setInterval(() => setElapsed(Math.round((Date.now() - startedAt) / 1000)), 1000);
    return () => {
      clearInterval(timer);
      setElapsed(0);
    };
  }, [running]);
  return running ? elapsed : 0;
}

/**
 * Нижня панель перегляду: що станеться після застосування, кнопка (з підтвердженням), хід застосування
 * і результат з посиланнями туди, де видно зміни.
 */
export function ApplyPanel({ job, onShowDecisions }: ApplyPanelProps) {
  const apply = useApplyImport(job.id);
  const applying = job.status === 'APPLYING';
  const elapsed = useElapsed(applying);

  const start = async () => {
    const ok = await confirm({
      title: `Застосувати «${job.fileName}»?`,
      description: APPLY_EFFECT[job.kind],
      confirmLabel: 'Застосувати',
      onConfirm: () => apply.mutateAsync(),
    });
    if (ok) notify.info('Застосовуємо імпорт — це може тривати до хвилини');
  };

  if (job.status === 'APPLIED') {
    const lines = job.result ? resultLines(job.result) : [];
    const versionId = job.result?.timetable?.versionId;
    const links =
      job.kind === 'TIMETABLE_XLSX'
        ? [
            { to: versionId ? `/admin/timetable?version=${versionId}` : '/admin/timetable', label: 'Відкрити редактор розкладу' },
            { to: '/admin/catalog/teachers', label: 'Перевірити викладачів' },
          ]
        : job.kind === 'ORDER_DOCUMENT'
          ? [{ to: '/admin/orders', label: 'До розпоряджень' }]
          : [{ to: '/admin/load', label: 'Відкрити навантаження' }];
    return (
      <section aria-label="Результат імпорту" className="flex flex-col gap-3 rounded-panel border border-add bg-add-subtle px-4 py-3.5">
        <p className="flex items-center gap-2 text-heading font-semibold text-add">
          <CircleCheck aria-hidden="true" className="size-5" />
          Імпорт застосовано
        </p>
        {lines.length > 0 ? (
          <ul className="flex flex-col gap-0.5 text-ui text-primary figures">
            {lines.map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
        ) : null}
        <div className="flex flex-wrap gap-2">
          {links.map((link) => (
            <Link key={link.to} to={link.to} className={buttonClass({ variant: 'secondary', size: 'sm' })}>
              {link.label}
              <ArrowRight aria-hidden="true" className="size-4" />
            </Link>
          ))}
        </div>
      </section>
    );
  }

  if (applying) {
    return (
      <section aria-label="Застосування імпорту" aria-live="polite" className="flex flex-col gap-2.5 rounded-panel border border-accent-subtle bg-selected px-4 py-3.5">
        <p className="flex items-center justify-between gap-3 text-ui font-semibold text-primary">
          <span>Застосовуємо: записуємо дані в систему…</span>
          <span className="text-caption font-normal text-muted figures">{elapsed} с</span>
        </p>
        <div
          role="progressbar"
          aria-label="Хід застосування"
          aria-valuetext={`Триває ${elapsed} с`}
          className="relative h-1.5 overflow-hidden rounded-full bg-surface"
        >
          <span className="absolute inset-y-0 w-1/3 rounded-full bg-accent motion-safe:animate-[nav-progress_1.4s_var(--ease-in-out)_infinite] motion-reduce:w-full motion-reduce:opacity-50" />
        </div>
        <p className="text-caption text-secondary">{APPLY_EFFECT[job.kind]}</p>
      </section>
    );
  }

  const blocked = job.status === 'NEEDS_REVIEW';
  const failed = job.status === 'FAILED';
  // сервер сам каже, чи можна застосовувати (READY або FAILED з чернеткою без блокувальних рядків)
  const canApply = job.canApply;

  return (
    <section
      aria-label="Застосування імпорту"
      className={cn(
        'flex flex-wrap items-center gap-3 rounded-panel border px-4 py-3',
        failed ? 'border-cancel bg-danger-subtle' : blocked ? 'border-change bg-change-subtle' : 'border-default bg-surface',
      )}
    >
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        {failed ? (
          <p className="flex items-center gap-2 text-ui font-semibold text-danger">
            <CircleX aria-hidden="true" className="size-4" />
            {job.error ?? 'Імпорт не вдався.'}
          </p>
        ) : blocked ? (
          <p className="text-ui font-semibold text-change">
            Щоб застосувати, вирішіть {countLabel(job.summary.conflicts, ['рядок', 'рядки', 'рядків'])} на вкладці «Рішення».
          </p>
        ) : (
          <p className="text-ui font-semibold text-primary">Готово до застосування</p>
        )}
        <p className="text-caption text-secondary">{failed && !canApply ? 'Чернетки немає — завантажте файл ще раз.' : APPLY_EFFECT[job.kind]}</p>
      </div>
      {blocked ? (
        <Button variant="secondary" onClick={onShowDecisions}>
          До рішень
        </Button>
      ) : null}
      <Button
        variant="primary"
        disabled={!canApply}
        loading={apply.isPending}
        iconStart={<Play aria-hidden="true" className="size-4" />}
        onClick={() => void start()}
      >
        {failed ? 'Спробувати ще раз' : 'Застосувати'}
      </Button>
    </section>
  );
}
