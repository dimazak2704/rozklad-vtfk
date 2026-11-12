import { useDeleteCatalogItem, useSaveCatalogItem, type CatalogEntity, type CatalogRequest, type CatalogRow } from '@/api/catalog';
import { qk } from '@/api/queryKeys';
import { skipUnchanged, trackSavedRecord } from '@/lib/undo/records';

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

/**
 * Журнал для довідників: створення скасовується видаленням (повтор створює запис знову, з новим id),
 * правка — поверненням попереднього тіла з версією, яку дав сервер після правки. Усі дії над одним
 * записом ділять його поточний id і версію (`lib/undo/records`), тож повтор створення не ламає
 * подальші правки. Якщо хтось змінив запис після вас, сервер відповість 409, і журнал скаже про це.
 */
export function useCatalogJournal<E extends CatalogEntity>(entity: E) {
  const save = useSaveCatalogItem(entity);
  const remove = useDeleteCatalogItem(entity);

  return {
    /** Правку збережено без змін — закрити форму без запиту й без запису в журналі (див. `skipUnchanged`). */
    skipUnchanged: (before: CatalogRequest<E> | null, after: CatalogRequest<E>, close: () => void) => skipUnchanged(before, after, close),
    saved({ before, after, saved, label }: SavedInput<E>) {
      return trackSavedRecord<CatalogRequest<E>>({
        kind: entity,
        label,
        invalidate: [qk.admin.catalog.all],
        before,
        after,
        saved,
        create: (body) => save.mutateAsync({ body }),
        update: (id, body) => save.mutateAsync({ id, body }),
        remove: (id) => remove.mutateAsync(id),
      });
    },
  };
}
