import { X } from 'lucide-react';
import { useId } from 'react';
import { useCatalogOptions, useSetTeacherDisciplines, useTeacherDisciplines } from '@/api/catalog';
import type { Uuid } from '@/api/types';
import { Combobox, ErrorState, IconButton, Skeleton, notify } from '@/components/ui';
import { lineAttr } from '@/lib/discipline-color';

interface CompetenceEditorProps {
  teacherId: Uuid;
  teacherLabel: string;
}

/**
 * Дисципліни, які викладач може вести. Це перший критерій кандидата на заміну (+50 балів, DOMAIN §7),
 * тому список варто тримати повним. Зберігається одразу.
 */
export function CompetenceEditor({ teacherId, teacherLabel }: CompetenceEditorProps) {
  const titleId = useId();
  const competences = useTeacherDisciplines(teacherId);
  const disciplines = useCatalogOptions('disciplines');
  const update = useSetTeacherDisciplines(teacherId);
  const current = competences.data ?? [];
  const chosen = new Set(current.map((d) => d.id));
  const options = (disciplines.items ?? [])
    .filter((d) => !chosen.has(d.id))
    .map((d) => ({ value: d.id, label: d.shortName, description: d.name, keywords: [d.name] }));

  const replace = (ids: Uuid[], success: string) =>
    update
      .mutateAsync(ids)
      .then(() => notify.success(success))
      .catch((error: unknown) => notify.error(error, 'Не вдалося оновити дисципліни викладача'));

  return (
    <section aria-labelledby={titleId} className="flex flex-col gap-2.5 border-t border-separator pt-4">
      <div className="flex flex-col gap-0.5">
        <h3 id={titleId} className="text-ui font-semibold text-primary">
          Дисципліни, які веде
        </h3>
        <p className="text-caption text-muted">
          За ними підбирають кандидатів на заміну: «Веде цю дисципліну» дає найбільше балів. Зберігаються одразу.
        </p>
      </div>
      {competences.isPending ? (
        <div className="flex gap-2" aria-hidden="true">
          <Skeleton className="h-7 w-20 rounded-full" />
          <Skeleton className="h-7 w-24 rounded-full" />
          <Skeleton className="h-7 w-16 rounded-full" />
        </div>
      ) : competences.isError ? (
        <ErrorState error={competences.error} onRetry={() => void competences.refetch()} size="sm" align="start" />
      ) : current.length === 0 ? (
        <p className="text-caption text-secondary">Дисциплін ще не додано — у ранжуванні замін викладач не отримає бонусу за фах.</p>
      ) : (
        <ul className="flex flex-wrap gap-1.5" aria-label={`Дисципліни викладача ${teacherLabel}`}>
          {current.map((discipline) => (
            <li
              key={discipline.id}
              title={discipline.name}
              className="inline-flex h-7 items-center gap-1.5 rounded-full border border-default bg-surface ps-2.5 pe-0.5 text-caption font-semibold text-primary"
            >
              <span {...lineAttr(discipline)} aria-hidden="true" className="size-2 rounded-full bg-[var(--line-dot)]" />
              {discipline.shortName}
              <IconButton
                label={`Прибрати дисципліну ${discipline.shortName}`}
                icon={<X aria-hidden="true" className="size-3.5" />}
                size="sm"
                className="size-6"
                disabled={update.isPending}
                onClick={() =>
                  void replace(
                    current.filter((d) => d.id !== discipline.id).map((d) => d.id),
                    `Дисципліну ${discipline.shortName} прибрано`,
                  )
                }
              />
            </li>
          ))}
        </ul>
      )}
      <div className="max-w-72">
        <Combobox
          value={null}
          onValueChange={(id) => {
            const picked = (disciplines.items ?? []).find((d) => d.id === id);
            if (picked) void replace([...current.map((d) => d.id), picked.id], `Додано дисципліну ${picked.shortName}`);
          }}
          options={options}
          placeholder="Додати дисципліну…"
          searchPlaceholder="Скорочення або назва"
          loading={disciplines.isPending}
          disabled={competences.isPending || update.isPending}
          aria-label={`Додати дисципліну викладачу ${teacherLabel}`}
          size="sm"
        />
      </div>
    </section>
  );
}
