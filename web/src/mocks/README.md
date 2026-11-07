# Мок-API (MSW)

SPA працює без бекенду й Keycloak: Mock Service Worker перехоплює запити до `/api` у браузері
(Service Worker) і у Vitest (`msw/node`) та відповідає даними з реального розкладу коледжу.
Лише для розробки й тестів: у production-збірці коду MSW, мок-даних і `mockServiceWorker.js` немає.

## Запуск

| Де | Як | Користувач |
|---|---|---|
| Розробка | `npm run dev:mock` → http://localhost:5180 (налаштування — `web/.env.mock`) | фіктивний ADMIN + TEACHER |
| Розробка з бекендом | `npm run dev` — без моків, `/api` іде через проксі на gateway :8080 | Keycloak |
| Vitest | завжди (`src/test/setup.ts`); запит без обробника — помилка тесту | — |
| Playwright | сервер стартує сам із `VITE_USE_MOCKS=1` на `PW_PORT` | гість; ролі — `loginWithMockRoles(page, 'ADMIN')` з `e2e/support/mock-auth.ts` до першого `goto` |

- Ролі у вкладці змінюються без перезапуску: `sessionStorage.setItem('rozklad.mockRoles', 'TEACHER')` і
  перезавантаження; `''` — гість; `sessionStorage.removeItem('rozklad.mockRoles')` — знову ролі з `.env.mock`.
  Працює лише з моками (`src/auth/roles.ts → devRoles`); з реальним бекендом ролі дає токен.
- Запит, для якого ще немає обробника, у браузері йде далі як є (`onUnhandledRequest: 'bypass'`) — на проксі,
  тобто до справжнього gateway, якщо той запущений, або отримає помилку проксі.
- Консоль браузера: `window.__rozkladMocks.emit('change.updated', dto)` шле подію SSE усім відкритим вкладкам
  адмінки чи кабінету, `window.__rozkladMocks.reset()` повертає сховища до початкових даних.

## Структура

```
src/mocks/
├── browser.ts     Service Worker (dev); main.tsx запускає його до першого рендеру
├── server.ts      msw/node для Vitest
├── registry.ts    збирає `handlers` з усіх handlers/*.ts (import.meta.glob)
├── kit.ts         apiPath, problem/badRequest/notFound/conflict/businessRule, затримка, збої,
│                  readJson, requiredParam, paginate
├── store.ts       createMockStore, createMockCollection, resetMockStores
├── events.ts      потік SSE і emitServerEvent
├── ids.ts         mockId(kind, key) — детермінований UUID
├── handlers/      одна область — один файл: meta.ts (meta, search), events.ts (SSE)
└── fixtures/      базові дані: catalog, calendar, timetable, schedule (генератор)
```

## Як додати обробники своєї області

Новий файл `handlers/<область>.ts` (як `src/api/<область>.ts`), що експортує масив `handlers`. Реєструвати
його ніде не треба. Приклад:

```ts
// src/mocks/handlers/absences.ts
import { http, HttpResponse } from 'msw';
import type { AbsenceDto, AbsenceRequest } from '@/api/types';
import { emitServerEvent } from '../events';
import { findTeacher, teacherNamed, teacherRef } from '../fixtures';
import { mockId } from '../ids';
import { apiPath, badRequest, readJson } from '../kit';
import { createMockCollection } from '../store';

// ДЕМО-запис, не факт із документів: синтетичні дані позначайте коментарем
const seed = (): AbsenceDto[] => [
  {
    id: mockId('absence', 'demo-krentsin-2026-11-12'),
    teacher: teacherRef(teacherNamed('Кренцін')),
    dateFrom: '2026-11-12',
    dateTo: '2026-11-12',
    pairs: null,
    reasonCategory: 'SICK_LEAVE',
    comment: null,
    source: 'ADMIN',
    status: 'ACTIVE',
  },
];
const absences = createMockCollection<AbsenceDto>('Відсутність', seed);

export const handlers = [
  http.get(apiPath('/admin/absences'), () => HttpResponse.json(absences.all())),

  http.post(apiPath('/admin/absences'), async ({ request }) => {
    const body = await readJson<AbsenceRequest>(request);
    const teacher = findTeacher(body.teacherId);
    if (!teacher) return badRequest('Невідомий викладач.', [{ field: 'teacherId', message: 'Оберіть викладача' }]);
    if (body.dateTo < body.dateFrom) {
      return badRequest('Перевірте дати.', [{ field: 'dateTo', message: 'Не раніше за дату початку' }]);
    }
    const absence = absences.insert({
      id: mockId('absence', crypto.randomUUID()),
      teacher: teacherRef(teacher),
      dateFrom: body.dateFrom,
      dateTo: body.dateTo,
      pairs: body.pairs ?? null,
      reasonCategory: body.reasonCategory,
      comment: body.comment ?? null,
      source: 'ADMIN',
      status: 'ACTIVE',
      createdAt: new Date().toISOString(),
    });
    emitServerEvent('absence.created', absence);
    return HttpResponse.json(absence, { status: 201 });
  }),

  http.delete(apiPath('/admin/absences/:id'), ({ params }) => {
    absences.remove(String(params.id)); // кидає 404 ProblemDetail, якщо такої немає
    return new HttpResponse(null, { status: 204 });
  }),
];
```

Правила:

1. **Шляхи — лише `apiPath('/…')`** і буквально як у `docs/CONTRACTS.md`; параметри — `:id`.
2. **Типи — з `@/api/types`.** Мок повертає те саме, що обіцяє контракт; розбіжність із контрактом — у
   `docs/CHANGELOG-CONTRACTS.md`, а не «підганяння» мока.
3. **Помилки — як у бекенді** (`problem`, `badRequest`, `notFound`, `conflict`, `businessRule`): `type`
   `urn:rozklad:problem:<slug>`, українські `title`/`detail`, `requestId`, `errors[]`, `conflicts[]`.
   Їх можна `return` або `throw` з будь-якого місця обробника (MSW візьме кинуту `Response` як відповідь).
   Валідуйте так само, як сервер: 400 на погані параметри, 404 на невідомий id, 409 з `conflicts[]`.
4. **Стан — у сховищах своєї області** (`createMockCollection`, `createMockStore`), а не в змінних модуля:
   так `resetMockStores()` скидає їх після кожного тесту. У браузері стан живе до перезавантаження сторінки.
5. **Дані — з `@/mocks/fixtures`.** Нових груп, викладачів, аудиторій не вигадуйте. Синтетичні записи
   (відсутності, заміни, розпорядження) позначайте коментарем як демо і по змозі беріть з реальних рядків
   (розпорядження №35 — `docs/DOMAIN.md` §11).
6. **Розклад зі змінами** — `buildSchedule(type, id, from, to, { overlay })` або `lessonsOn(date, { overlay })`:
   `overlay` отримує всі заняття дати (усіх груп) і повертає змінений список, тож заміну бачать і група,
   і обидва викладачі. Межі дат — `scheduleRangeError(from, to)` (не більше 31 дня) → `badRequest`.
7. **Живі оновлення:** після зміни стану — `emitServerEvent('change.updated', dto)` тощо.
8. **Тести обробника** — через справжній клієнт (`api.get(...)`), див. `handlers/meta.test.ts`. Інша відповідь
   в окремому тесті: `server.use(http.get(apiPath('/…'), () => problem({ status: 503 })))`.

## Затримка й збої

- **Затримка** (лише в браузері): кожна змокована відповідь чекає `real` (100–400 мс, як справжній сервер).
  Змінити — `VITE_MOCK_DELAY` у `.env.mock` (`0`, `1500`, `real`, `infinite`) або прапорцем
  `?mockDelay=…` в адресі сторінки чи запиту.
- **Збій:** `?mockError=503` в адресі сторінки — усі запити `/api` отримують ProblemDetail з цим статусом;
  `&mockErrorPath=/public/search` — лише шляхи, що містять цей фрагмент; `mockError=network` — обрив мережі.
  Приклад: `http://localhost:5180/g/4КІ1?mockError=500&mockErrorPath=/public/schedule`. Прапорець живе в
  адресі поточної сторінки: після переходу в SPA він зникає (так задумано — «це завантаження з помилкою»).
- Прапорці обробляє спільна прослойка `controlHandler` (`kit.ts`) перед обробниками областей. У Vitest
  прослойки немає — там помилку задають через `server.use(...)`.

## Базовий набір даних (`fixtures/`)

Джерело — реальна таблиця `seed/schedule-2025-26.xlsx` (аркуш «Розклад друк», I семестр 2025/26),
перенесена на I семестр 2026/2027, як демо-сід бекенду (`docs/DOMAIN.md` §12).

| Модуль | Що там |
|---|---|
| `timetable.data.ts` | ЗГЕНЕРОВАНО: 56 груп (код, курс, домашня аудиторія, куратор) і 1077 занять шаблону. Перегенерувати: `python -I web/scripts/mocks/extract_timetable.py seed/schedule-2025-26.xlsx web/src/mocks/fixtures/timetable.data.ts` (з кореня репозиторію) |
| `catalog.ts` | `GROUPS`, `TEACHERS` (107), `DISCIPLINES` (128), `ROOMS` (45), `DEPARTMENTS`, `SPECIALTIES`; `find…`, `…Named`, `…Ref`, `toPublic…` |
| `calendar.ts` | семестри (I активний: 01.09–30.12.2026, тиждень 1 — чисельник), `parityOn`, `semesterWeekNumber`, `semesterWeeks`, `isStudyDay`, профілі дзвінків («2 зміни, 60 хв» активний), `SHIFT_WINDOWS`, `CALENDAR_PERIODS` (практика 2КІ3, дистанційне навчання 3 курсу й 4КІ) |
| `timetable.ts` | `TEMPLATE_LESSONS` (TemplateLessonDto опублікованої версії, потоки позначені `streamId`), `PUBLISHED_VERSION_ID`, `disciplinesOfTeacher` |
| `schedule.ts` | `buildSchedule`, `lessonsOn`, `scheduleOwner`, `scheduleRangeError`, `weekdayLabel` |

- Перевірено тестами: 19.10.2026 — 8 тиждень, знаменник (як у розпорядженні №35); тиждень 4КІ1 збігається з
  таблицею; кожне заняття має дзвінок і лежить у вікні зміни своєї групи; id стабільні й унікальні.
- Прізвища й ініціали — як у таблиці (ініціали куратора підтягуються до прізвища, якщо однофамільців немає);
  виправлено лише одруківки з `DOMAIN.md` §10 і ще дві очевидні («Вернигорв», «Шидлорвська»).
- Повні назви — лише для відомих абревіатур (`DOMAIN.md` §4, §10); решта дисциплін і спеціальностей мають
  абревіатуру як назву і `needsReview: true`. Місткість, корпус, циклова комісія, розмір групи, ім'я та
  по батькові викладача — `null`: у документах їх немає. Вид заняття (`lessonKind`) у таблиці не вказано — `MIXED`.

## Що вже змоковано

| Ендпоінт | Файл |
|---|---|
| `GET /api/public/meta` | `handlers/meta.ts` (київський час, поточна й наступна пара) |
| `GET /api/public/search?q=&limit=` | `handlers/meta.ts` (нечіткий пошук з `lib/search`, `limit` 1–50) |
| `GET /api/events/stream` | `handlers/events.ts` (відкритий потік, `ping` кожні 25 с) |

Решта ендпоінтів — справа агентів сторінок: кожен додає свій `handlers/<область>.ts`.
