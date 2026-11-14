# API-шар SPA

Як сторінки ходять до бекенду. Коротко: **один файл на область** (`src/api/<область>.ts`) з хуками
TanStack Query, типи — лише з `types.ts`, ключі — лише з `queryKeys.ts`, запити — лише через `api` з `client.ts`.

## Що вже є

| Файл | Що робить |
|---|---|
| `client.ts` | `api.get/post/put/patch/delete/upload/blob/file`: база `/api`, Bearer-токен (якщо увійшли), тайм-аут 30 с, ProblemDetail → `ApiError` |
| `errors.ts` | `ApiError` (`status`, `kind`, `title`, `detail`, `errors[]`, `conflicts[]`, `requestId`, `fieldErrors()`), `errorMessage()` |
| `types.ts` | TypeScript-типи для **всіх** DTO з `docs/CONTRACTS.md`. Виведені з DOMAIN поля позначені `@inferred` |
| `queryKeys.ts` | Ієрархічні ключі `qk.public…`, `qk.me…`, `qk.admin…`, `qk.importer…`, `qk.notify…` |
| `queryClient.ts` | Політика кешу: `staleTime` 30 с, повтор лише мережевих збоїв і 5xx/429 (до 2 разів), мутації без повторів |
| `meta.ts` | Запити оболонки: `usePublicMeta()`, `usePublicSearch(q)`, `useBells(date)` |
| `sse.ts`, `useEventStream.ts` | Потік `GET /api/events/stream` через fetch (з Bearer), перепідключення з backoff, `Last-Event-ID` |
| `live.ts` | `useLiveConnection()` (уже викликано в макетах адмінки й кабінету) і `useServerEvent(type, handler)` для сторінок |

## Мок-API

Без бекенду: `npm run dev:mock` — MSW відповідає на `/api` даними з реального розкладу (Vitest і e2e — теж
з моками). Свою область змокуйте поруч з її хуками: `src/mocks/handlers/<область>.ts`. Правила, дані й
прапорці `?mockError=` / `?mockDelay=` — `src/mocks/README.md`.

## Як додати свою область

```ts
// src/api/changes.ts — приклад для сторінки «Заміни»
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { notify } from '@/components/ui';
import { api } from './client';
import { qk } from './queryKeys';
import type { ChangeDto, CreateChangeRequest, IsoDate } from './types';

export function useChanges(date: IsoDate, status?: string) {
  return useQuery({
    queryKey: qk.admin.changes.list({ date, status }),
    queryFn: ({ signal }) => api.get<ChangeDto[]>('/admin/changes', { date, status }, { signal }),
  });
}

export function useCreateChange() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateChangeRequest) => api.post<ChangeDto>('/admin/changes', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: qk.admin.changes.all }),
    onError: (error) => notify.error(error, 'Не вдалося створити заміну'),
  });
}
```

Правила:

1. **Ключі — лише з `qk`.** Потрібна нова гілка — додайте її в `queryKeys.ts` (одна гілка на область). Так
   інвалідація з SSE (`live.ts`) і з мутацій працює префіксом.
2. **`signal` передавайте завжди** (`queryFn: ({ signal }) => api.get(..., { signal })`) — скасовані запити не
   перетирають свіжі дані.
3. **Публічні ендпоінти** (`/public/**`) — з `{ auth: false }`: токен там не потрібен. Виняток — розклад і заміни:
   гостю сервер віддає лише останні дні (`publicHistoryFrom` у meta), персоналу — увесь архів. Там
   `viewer` у ключі запиту (`usePublicViewer`, `qk.public.schedule|changes`), а персонал питає з
   `auth: 'required'`: без дійсного токена запит не йде гостьовим (відповідь гостю лягла б під ключ персоналу),
   а завершується 401 — токен забувається, і сторінка перепитує як гість. 404 `history-not-public`
   (`error.isHistoryNotPublic`) сторінка показує як межу архіву, не як збій. Запити з токеном Service Worker
   не кешує (`public-api-cache.ts`): токен лишився б у Cache Storage після виходу.
4. **Помилки не ховайте.** У компоненті — `<ErrorState error={query.error} onRetry={query.refetch} />` або стан
   таблиці; у мутаціях — `notify.error(error, 'Що не вдалося')`. Помилки полів форми: `error.fieldErrors()` →
   `form.setError(field, { message })`. Конфлікти розкладу: `error.conflicts` (масив `Conflict`).
5. **Оптимістичні правки** (UI.md §3): `onMutate` → `setQueryData`, `onError` → відкат, і `notify.undo(...)` для
   скасування. Небезпечні дії — через `confirm({ title, objectName, tone: 'danger' })`.
6. **Типи.** Нове поле в контракті → оновіть `types.ts` у своєму блоці. Якщо бекенд повертає не те, що в
   контракті, не підганяйте тип мовчки: запишіть розбіжність у `docs/CHANGELOG-CONTRACTS.md`.
7. **Живі оновлення.** Сторінка, якій треба більше, ніж стандартна інвалідація, підписується:
   `useServerEvent('change.updated', (change) => …)`. Нове зʼєднання SSE на сторінці не відкривайте.

## Файли й завантаження

- Завантаження: `api.upload<ImportJobDto>('/import/jobs', form)` (multipart, тайм-аут 120 с).
- Файли розпоряджень: `api.blob('/documents/orders/' + id + '.pdf')` → `URL.createObjectURL(blob)`; `api.file(…)` —
  те саме з назвою з `Content-Disposition`. На публічній сторінці персоналу — `openOrderDocument(id, format, title)`
  (вкладка відкривається в тому ж натисканні; заблокована — файл під назвою від сервера; закрита людиною —
  скасування), гостю — звичайне посилання `orderDocumentUrl(id, format)`.

## Чого не робити

- Не викликати `fetch` напряму (оминете токен, тайм-аут і розбір помилок).
- Не писати ключі руками в компонентах (`['admin', 'changes']`) — лише `qk`.
- Не тримати серверні дані в Zustand: сервер → TanStack Query, інтерфейс → Zustand (`src/stores`).
