import { useSaveCatalogItem, type CatalogRequest, type CatalogRow } from '@/api/catalog';
import { notify } from '@/components/ui';

type Toggleable = 'rooms' | 'disciplines' | 'teachers' | 'groups';

/**
 * «Зробити неактивним / активним» однією дією з можливістю скасувати (UI.md §3: дрібна правка + undo).
 * Повертає функцію, яку можна викликати з меню рядка чи з діалогу видалення.
 */
export function useToggleActive<E extends Toggleable>(
  entity: E,
  toRequest: (row: CatalogRow<E>, patch: { active: boolean }) => CatalogRequest<E>,
  message: (row: CatalogRow<E>, active: boolean) => string,
) {
  const save = useSaveCatalogItem(entity);
  return async (row: CatalogRow<E>, options: { undo?: boolean } = {}) => {
    const next = !row.active;
    const updated = await save.mutateAsync({ id: row.id, body: toRequest(row, { active: next }) });
    if (options.undo === false) return updated;
    notify.undo(message(row, next), {
      onUndo: () => {
        save
          .mutateAsync({ id: row.id, body: toRequest(updated, { active: row.active }) })
          .catch((error: unknown) => notify.error(error, 'Не вдалося повернути попередній стан'));
      },
    });
    return updated;
  };
}
