import { useDeleteCatalogItem, useSaveCatalogItem, type CatalogEntity, type CatalogRequest, type CatalogRow } from '@/api/catalog';
import { qk } from '@/api/queryKeys';
import { track } from '@/lib/undo/actions';

export interface SavedInput<E extends CatalogEntity> {
  /** Тіло запиту до правки (null — запис створено). Збирається тим самим кодом, що й тіло правки. */
  before: CatalogRequest<E> | null;
  /** Тіло, яке надіслала форма. */
  after: CatalogRequest<E>;
  /** Відповідь сервера: id і нова версія запису. */
  saved: CatalogRow<E>;
  /** «Додано аудиторію 305», «Змінено аудиторію 305». */
  label: string;
}

const withVersion = <B,>(body: B, version: number | undefined): B => ({ ...body, version });

/**
 * Журнал для довідників: створення скасовується видаленням (повтор створює запис знову, з новим id),
 * правка — поверненням попереднього тіла з версією, яку дав сервер після правки. Якщо хтось змінив
 * запис після вас, сервер відповість 409, і журнал скаже про це, а не перезапише чужу правку.
 */
export function useCatalogJournal<E extends CatalogEntity>(entity: E) {
  const save = useSaveCatalogItem(entity);
  const remove = useDeleteCatalogItem(entity);
  const invalidate = [qk.admin.catalog.all];

  return {
    saved({ before, after, saved, label }: SavedInput<E>) {
      if (before === null) {
        let id = saved.id;
        return track({
          label,
          invalidate,
          undo: () => remove.mutateAsync(id),
          redo: async () => {
            const again = await save.mutateAsync({ body: withVersion(after, undefined) });
            id = again.id;
          },
        });
      }
      let version = saved.version;
      const put = async (body: CatalogRequest<E>) => {
        const result = await save.mutateAsync({ id: saved.id, body: withVersion(body, version) });
        version = result.version;
      };
      return track({ label, invalidate, undo: () => put(before), redo: () => put(after) });
    },
  };
}
