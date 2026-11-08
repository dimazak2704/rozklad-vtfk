import { Check, Pencil, Plus, Trash, X } from 'lucide-react';
import { Fragment, useId, useState } from 'react';
import { useDeleteSubgroup, useSaveSubgroup, useSubgroups, type CatalogSubgroup } from '@/api/catalog';
import { errorMessage } from '@/api/errors';
import type { Uuid } from '@/api/types';
import { Button, ErrorState, IconButton, Input, NumberInput, Skeleton, notify } from '@/components/ui';
import { countLabel } from '@/lib/labels';

interface SubgroupEditorProps {
  groupId: Uuid;
  groupCode: string;
}

interface Draft {
  id: Uuid | null;
  number: number;
  name: string;
  size: number | null;
}

/**
 * Підгрупи групи (DOMAIN §1): потрібні для лабораторних та іноземної мови. Номер 0 — уся група, його
 * не зберігають. Підгрупу з розкладу чи навантаження сервер не видаляє й пояснює чому.
 */
export function SubgroupEditor({ groupId, groupCode }: SubgroupEditorProps) {
  const titleId = useId();
  const subgroups = useSubgroups(groupId);
  const save = useSaveSubgroup(groupId);
  const remove = useDeleteSubgroup(groupId);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const list = subgroups.data ?? [];

  const startNew = () => {
    const used = new Set(list.map((s) => s.number));
    let number = 1;
    while (used.has(number)) number += 1;
    setProblem(null);
    setDraft({ id: null, number, name: '', size: null });
  };

  const commit = async () => {
    if (!draft) return;
    setProblem(null);
    const existing = list.find((s) => s.id === draft.id);
    try {
      await save.mutateAsync({
        subgroupId: draft.id,
        body: { number: draft.number, name: draft.name.trim() || null, size: draft.size, version: existing?.version },
      });
      notify.success(draft.id ? `Підгрупу ${draft.number} збережено` : `Додано підгрупу ${draft.number} групи ${groupCode}`);
      setDraft(null);
    } catch (error) {
      setProblem(errorMessage(error));
    }
  };

  const drop = async (subgroup: CatalogSubgroup) => {
    setProblem(null);
    try {
      await remove.mutateAsync(subgroup.id);
      notify.success(`Підгрупу ${subgroup.number} групи ${groupCode} видалено`);
    } catch (error) {
      setProblem(errorMessage(error));
    }
  };

  const editor = (value: Draft) => (
    <li className="flex flex-wrap items-end gap-2 rounded-control border border-accent-subtle bg-selected p-2">
      <label className="flex flex-col gap-1 text-caption font-semibold text-secondary">
        Номер
        <NumberInput
          size="sm"
          value={value.number}
          onValueChange={(next) => setDraft({ ...value, number: next ?? 1 })}
          min={1}
          max={9}
          className="w-24"
          aria-label="Номер підгрупи"
        />
      </label>
      <label className="flex min-w-40 flex-1 flex-col gap-1 text-caption font-semibold text-secondary">
        Назва
        <Input size="sm" value={value.name} placeholder={`${value.number} підгрупа`} onChange={(event) => setDraft({ ...value, name: event.target.value })} />
      </label>
      <label className="flex flex-col gap-1 text-caption font-semibold text-secondary">
        Студентів
        <NumberInput size="sm" value={value.size} onValueChange={(next) => setDraft({ ...value, size: next })} min={0} max={200} className="w-28" aria-label="Кількість студентів" />
      </label>
      <div className="flex gap-1">
        <IconButton label="Зберегти підгрупу" icon={<Check aria-hidden="true" className="size-4" />} variant="primary" size="sm" loading={save.isPending} onClick={() => void commit()} />
        <IconButton label="Скасувати" icon={<X aria-hidden="true" className="size-4" />} size="sm" onClick={() => setDraft(null)} disabled={save.isPending} />
      </div>
    </li>
  );

  return (
    <section aria-labelledby={titleId} className="flex flex-col gap-2.5 border-t border-separator pt-4">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div className="flex flex-col gap-0.5">
          <h3 id={titleId} className="text-ui font-semibold text-primary">
            Підгрупи
          </h3>
          <p className="text-caption text-muted">Для лабораторних та іноземної мови. Зміни зберігаються одразу.</p>
        </div>
        {draft === null ? (
          <Button size="sm" variant="secondary" iconStart={<Plus aria-hidden="true" className="size-4" />} onClick={startNew} disabled={subgroups.isPending}>
            Додати підгрупу
          </Button>
        ) : null}
      </div>
      {subgroups.isPending ? (
        <Skeleton className="h-10 w-full" />
      ) : subgroups.isError ? (
        <ErrorState error={subgroups.error} onRetry={() => void subgroups.refetch()} size="sm" align="start" />
      ) : (
        <ul className="flex flex-col gap-1.5">
          {list.length === 0 && draft === null ? (
            <li className="text-caption text-secondary">Групу не поділено: усі заняття — для всієї групи.</li>
          ) : null}
          {list.map((subgroup) =>
            draft?.id === subgroup.id ? (
              <Fragment key={subgroup.id}>{editor(draft)}</Fragment>
            ) : (
              <li key={subgroup.id} className="flex items-center gap-3 rounded-control border border-default px-3 py-1.5">
                <span className="font-condensed text-heading font-bold figures text-primary">{subgroup.number}</span>
                <span className="min-w-0 flex-1 truncate text-ui text-primary">{subgroup.name ?? `${subgroup.number} підгрупа`}</span>
                <span className="text-caption text-muted figures">
                  {subgroup.size !== null ? countLabel(subgroup.size, ['студент', 'студенти', 'студентів']) : 'розмір невідомий'}
                </span>
                <IconButton
                  label={`Редагувати підгрупу ${subgroup.number}`}
                  icon={<Pencil aria-hidden="true" className="size-4" />}
                  size="sm"
                  disabled={draft !== null}
                  onClick={() => {
                    setProblem(null);
                    setDraft({ id: subgroup.id, number: subgroup.number, name: subgroup.name ?? '', size: subgroup.size });
                  }}
                />
                <IconButton
                  label={`Видалити підгрупу ${subgroup.number}`}
                  icon={<Trash aria-hidden="true" className="size-4" />}
                  variant="danger-ghost"
                  size="sm"
                  loading={remove.isPending && remove.variables === subgroup.id}
                  disabled={draft !== null}
                  onClick={() => void drop(subgroup)}
                />
              </li>
            ),
          )}
          {draft !== null && draft.id === null ? editor(draft) : null}
        </ul>
      )}
      {problem ? (
        <p role="alert" className="rounded-control border border-change bg-change-subtle px-3 py-2 text-caption font-medium text-change">
          {problem}
        </p>
      ) : null}
    </section>
  );
}
