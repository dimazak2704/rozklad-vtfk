import { useState } from 'react';
import type { CalendarSemester } from '@/api/calendar';
import { errorMessage } from '@/api/errors';
import { useDeriveLoad, useTimetableVersions, type DerivedLoadResult } from '@/api/load';
import { Button, Dialog, DialogContent, EmptyState, ErrorState, Plate, Select, Skeleton, notify } from '@/components/ui';
import { formatNumericDate } from '@/lib/date';
import { countLabel } from '@/lib/labels';

interface DeriveDialogProps {
  semester: CalendarSemester;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

const STATUS_LABEL = { DRAFT: 'чернетка', PUBLISHED: 'опублікована', ARCHIVED: 'архів' } as const;

/**
 * «Вивести з розкладу»: навантаження з тижневого шаблону версії (пара щотижня — 1, лише чисельник чи
 * знаменник — ½). Записи, внесені вручну чи з плану, не перезаписуються — їх показано окремо.
 */
export function DeriveDialog({ semester, open, onOpenChange }: DeriveDialogProps) {
  const versions = useTimetableVersions(semester.id);
  const derive = useDeriveLoad();
  const [versionId, setVersionId] = useState<string | null>(null);
  const [result, setResult] = useState<DerivedLoadResult | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const list = versions.data ?? [];
  const chosen = versionId ?? list.find((v) => v.status === 'PUBLISHED')?.id ?? list[0]?.id ?? null;

  const run = async () => {
    if (!chosen) return;
    setProblem(null);
    try {
      const outcome = await derive.mutateAsync(chosen);
      setResult(outcome);
      notify.success(`Навантаження виведено: нових ${outcome.created}, оновлено ${outcome.updated}`);
    } catch (error) {
      setProblem(errorMessage(error));
    }
  };

  return (
    <Dialog open={open} onOpenChange={(next) => !derive.isPending && onOpenChange(next)}>
      <DialogContent
        title="Вивести навантаження з розкладу"
        description="Пара щотижня — 1 пара на тиждень, лише чисельник чи знаменник — ½. Плановий обсяг: години × навчальні тижні."
        size="lg"
        footer={
          result ? (
            <Button variant="primary" onClick={() => onOpenChange(false)}>
              Готово
            </Button>
          ) : (
            <>
              <Button variant="secondary" onClick={() => onOpenChange(false)} disabled={derive.isPending}>
                Скасувати
              </Button>
              <Button variant="primary" loading={derive.isPending} disabled={!chosen} onClick={() => void run()}>
                Вивести
              </Button>
            </>
          )
        }
      >
        {result ? (
          <div className="flex flex-col gap-4" aria-live="polite">
            <dl className="grid grid-cols-3 gap-3">
              {[
                { label: 'Нових записів', value: result.created },
                { label: 'Оновлено', value: result.updated },
                { label: 'Прибрано', value: result.removed },
              ].map((stat) => (
                <div key={stat.label} className="flex flex-col gap-0.5 rounded-panel border border-default px-3 py-2">
                  <dt className="text-caption text-muted">{stat.label}</dt>
                  <dd className="font-condensed text-headline font-bold figures text-primary">{stat.value}</dd>
                </div>
              ))}
            </dl>
            {result.skipped.length > 0 ? (
              <section className="flex flex-col gap-2">
                <h3 className="text-ui font-semibold text-primary">
                  Не перезаписано: {countLabel(result.skipped.length, ['запис', 'записи', 'записів'])}
                </h3>
                <p className="text-caption text-muted">{result.skipped[0]?.reason}</p>
                <ul className="flex max-h-48 flex-col gap-1 overflow-y-auto">
                  {result.skipped.map((skip) => (
                    <li key={`${skip.group.id}-${skip.discipline.id}`} className="flex items-center gap-2 text-ui">
                      <Plate size="sm">{skip.group.code}</Plate>
                      <span className="font-semibold">{skip.discipline.shortName}</span>
                      <span className="truncate text-caption text-muted" title={skip.discipline.name}>{skip.discipline.name}</span>
                    </li>
                  ))}
                </ul>
              </section>
            ) : (
              <p className="text-ui text-secondary">Усі записи з розкладу оновлено, ручних правок не зачеплено.</p>
            )}
          </div>
        ) : versions.isPending ? (
          <Skeleton className="h-9 w-full" />
        ) : versions.isError ? (
          <ErrorState error={versions.error} onRetry={() => void versions.refetch()} size="sm" />
        ) : list.length === 0 ? (
          <EmptyState
            size="sm"
            title="У семестрі немає версій розкладу"
            description="Імпортуйте таблицю розкладу або складіть розклад у редакторі — тоді навантаження можна буде вивести з нього."
          />
        ) : (
          <div className="flex flex-col gap-3">
            <label className="flex flex-col gap-1.5 text-ui font-semibold text-primary">
              Версія розкладу
              <Select<string>
                value={chosen}
                onValueChange={setVersionId}
                options={list.map((v) => ({
                  value: v.id,
                  label: v.name,
                  description: `${STATUS_LABEL[v.status]} · ${v.lessonCount} занять${v.effectiveFrom ? ` · діє з ${formatNumericDate(v.effectiveFrom)}` : ''}`,
                }))}
              />
            </label>
            {problem ? (
              <p role="alert" className="text-caption font-medium text-danger">
                {problem}
              </p>
            ) : null}
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
