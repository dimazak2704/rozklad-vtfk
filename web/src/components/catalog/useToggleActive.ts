import { useSaveCatalogItem, type CatalogRequest, type CatalogRow } from '@/api/catalog';
import { qk } from '@/api/queryKeys';
import { track } from '@/lib/undo/actions';

type Toggleable = 'rooms' | 'disciplines' | 'teachers' | 'groups';

/**
 * «Зробити неактивним / активним» однією дією з можливістю скасувати (UI.md §3: дрібна правка + undo).
 * Дія потрапляє в журнал: Ctrl+Z чи «Скасувати» в тості повертають попередній стан з версією,
 * яку сервер дав після зміни (чужа правка за цей час не перезапишеться). Повертає функцію,
 * яку можна викликати з меню рядка чи з діалогу видалення.
 */
export function useToggleActive<E extends Toggleable>(
  entity: E,
  toRequest: (row: CatalogRow<E>, patch: { active: boolean; version?: number }) => CatalogRequest<E>,
  message: (row: CatalogRow<E>, active: boolean) => string,
) {
  const save = useSaveCatalogItem(entity);
  return async (row: CatalogRow<E>) => {
    const next = !row.active;
    const updated = await save.mutateAsync({ id: row.id, body: toRequest(row, { active: next }) });
    let version = updated.version;
    const put = async (active: boolean) => {
      const result = await save.mutateAsync({ id: row.id, body: toRequest(updated, { active, version }) });
      version = result.version;
    };
    track({
      label: message(row, next),
      invalidate: [qk.admin.catalog.all],
      undo: () => put(row.active),
      redo: () => put(next),
    });
    return updated;
  };
}
