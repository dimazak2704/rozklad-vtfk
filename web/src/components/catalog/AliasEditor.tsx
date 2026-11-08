import { Plus, X } from 'lucide-react';
import { useId, useState } from 'react';
import { useAddAlias, useAliases, useRemoveAlias, type AliasOwner } from '@/api/catalog';
import { errorMessage } from '@/api/errors';
import type { Uuid } from '@/api/types';
import { Button, ErrorState, IconButton, Input, Skeleton, notify } from '@/components/ui';

interface AliasEditorProps {
  owner: AliasOwner;
  ownerId: Uuid;
  /** «Кренцін М.Д.», «СТП» — для підписів кнопок. */
  ownerLabel: string;
  /** Приклад написання для підказки поля. */
  example: string;
}

/**
 * Псевдоніми: як запис пишуть у таблицях і розпорядженнях. Імпорт і пошук знаходять запис за ними.
 * Зберігаються одразу (окремо від кнопки «Зберегти» форми) — про це сказано в підписі.
 */
export function AliasEditor({ owner, ownerId, ownerLabel, example }: AliasEditorProps) {
  const inputId = useId();
  const aliases = useAliases(owner, ownerId);
  const add = useAddAlias(owner, ownerId);
  const remove = useRemoveAlias(owner, ownerId);
  const [draft, setDraft] = useState('');
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    const alias = draft.trim();
    if (!alias) {
      setError('Введіть написання, яке треба розпізнавати.');
      return;
    }
    setError(null);
    try {
      await add.mutateAsync(alias);
      setDraft('');
      notify.success(`Псевдонім «${alias}» додано`);
    } catch (cause) {
      setError(errorMessage(cause));
    }
  };

  return (
    <section aria-labelledby={`${inputId}-title`} className="flex flex-col gap-2.5 border-t border-separator pt-4">
      <div className="flex flex-col gap-0.5">
        <h3 id={`${inputId}-title`} className="text-ui font-semibold text-primary">
          Псевдоніми
        </h3>
        <p className="text-caption text-muted">
          Інші написання з таблиць і розпоряджень — імпорт і пошук розпізнаватимуть їх. Зберігаються одразу.
        </p>
      </div>
      {aliases.isPending ? (
        <div className="flex gap-2" aria-hidden="true">
          <Skeleton className="h-7 w-24 rounded-full" />
          <Skeleton className="h-7 w-20 rounded-full" />
        </div>
      ) : aliases.isError ? (
        <ErrorState error={aliases.error} onRetry={() => void aliases.refetch()} size="sm" align="start" />
      ) : aliases.data.length === 0 ? (
        <p className="text-caption text-secondary">Псевдонімів немає.</p>
      ) : (
        <ul className="flex flex-wrap gap-1.5" aria-label={`Псевдоніми: ${ownerLabel}`}>
          {aliases.data.map((alias) => (
            <li
              key={alias.id}
              className="inline-flex h-7 items-center gap-1 rounded-full border border-default bg-sunken ps-3 pe-0.5 text-caption font-semibold text-primary"
            >
              {alias.alias}
              <IconButton
                label={`Прибрати псевдонім «${alias.alias}»`}
                icon={<X aria-hidden="true" className="size-3.5" />}
                size="sm"
                className="size-6"
                loading={remove.isPending && remove.variables === alias.id}
                onClick={() =>
                  remove
                    .mutateAsync(alias.id)
                    .then(() => notify.success(`Псевдонім «${alias.alias}» прибрано`))
                    .catch((cause: unknown) => notify.error(cause, 'Не вдалося прибрати псевдонім'))
                }
              />
            </li>
          ))}
        </ul>
      )}
      <div className="flex flex-col gap-1">
        <label htmlFor={inputId} className="sr-only">
          Новий псевдонім для {ownerLabel}
        </label>
        <div className="flex gap-2">
          <Input
            id={inputId}
            size="sm"
            value={draft}
            placeholder={`Наприклад, ${example}`}
            autoComplete="off"
            spellCheck={false}
            aria-invalid={error ? true : undefined}
            aria-describedby={error ? `${inputId}-error` : undefined}
            onChange={(event) => {
              setDraft(event.target.value);
              if (error) setError(null);
            }}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.preventDefault();
                void submit();
              }
            }}
            className="max-w-64"
          />
          <Button size="sm" variant="secondary" loading={add.isPending} iconStart={<Plus aria-hidden="true" className="size-4" />} onClick={() => void submit()}>
            Додати
          </Button>
        </div>
        {error ? (
          <p id={`${inputId}-error`} role="alert" className="text-caption font-medium text-danger">
            {error}
          </p>
        ) : null}
      </div>
    </section>
  );
}
