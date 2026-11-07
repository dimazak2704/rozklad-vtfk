# Модуль web-foundation — основа SPA «Розклад ВТФК»

Каркас вебзастосунку в `web/`: дизайн-система, макети, реєстр маршрутів, авторизація, API-шар, стан,
утиліти, детектор «AI slop», Docker. Сторінки поки що заглушки — їх наповнюють агенти сторінок, не чіпаючи
роутер і спільні компоненти.

## Запуск і перевірки

```bash
cd web
npm install
npm run dev          # http://localhost:5180 (strictPort), /api → http://localhost:8080
npm run build        # tsc -b && vite build
npm run lint         # eslint (typed) + scripts/slop-check.mjs
npm test             # vitest: unit + компоненти + скрипти
npm run e2e          # playwright (desktop 1440 + mobile 360); браузер: npx playwright install chromium
                     # або PW_CHROMIUM_PATH=…/chrome.exe npm run e2e на вже встановленому Chromium
```

Сторінка всіх компонентів (лише dev): **http://localhost:5180/dev/kitchen-sink** — обидві теми, «час демо»,
стани клітинок, тиждень, день, таблиця, шари.

Без Keycloak адмінку й кабінет можна дивитися з фіктивним користувачем: `web/.env.development.local` з
`VITE_DEV_AUTH_ROLES=ADMIN,TEACHER` (лише `npm run dev`; у production-збірці гілка вирізається). Без бекенду
запити повертають 502 — сторінки мають показувати `ErrorState`, а не падати.

Змінні (`import.meta.env`, див. `web/.env.example`): `VITE_API_BASE` (`/api`), `VITE_OIDC_AUTHORITY`
(`http://localhost:8180/realms/rozklad`), `VITE_OIDC_CLIENT_ID` (`rozklad-web`), `VITE_OIDC_SCOPE`,
`VITE_TELEGRAM_BOT_URL`, `VITE_DEV_API_TARGET`, `VITE_DEV_AUTH_ROLES`, `VITE_QUERY_DEVTOOLS=1`.

Docker: `docker build -t rozklad-web web/` → nginx на 80 (у compose — 8088): SPA-fallback, `/api` → `gateway:8080`
без буферизації (SSE), gzip, `/assets` кешуються назавжди, `index.html` — ні, `/healthz`, CSP із хешем вбудованого
скрипту теми (тест `scripts/csp-hash.test.mjs` стежить, щоб хеш збігався з `index.html`).

## Структура

```
web/
├── DESIGN.md, PRODUCT.md          дизайн-система (токени + правила) і продуктова правда
├── index.html                     lang="uk", скрипт теми до першого кадру
├── nginx.conf, Dockerfile
├── scripts/slop-check.mjs         детектор шаблонного дизайну (у npm run lint)
├── e2e/                           Playwright
└── src/
    ├── app/routes.ts              РЕЄСТР МАРШРУТІВ (UI.md §1) + навігація + paths
    ├── app/router.tsx, App.tsx    дерево маршрутів, провайдери
    ├── layouts/                   RootLayout, PublicLayout, TeacherLayout, AdminLayout
    ├── auth/                      UserManager (PKCE), useCurrentUser, RoleGate, postLogin
    ├── api/                       client, errors, types (усі DTO), queryKeys, queryClient, SSE, meta, live
    ├── stores/                    тема, бічна панель, робоча дата, вибране, палітра, скорочення, confirm
    ├── hooks/                     useNow, useHotkeys, useWorkingDate, useResolvedTheme, useDebouncedValue
    ├── lib/                       date (Київ), labels, search, bells, schedule, discipline-color, sort, cn…
    ├── components/ui/             базові компоненти (barrel: '@/components/ui')
    ├── components/schedule/       LessonCell, LessonDetails, DayList, DayColumn, WeekGrid, NowIndicator
    ├── components/shell/          шапка, бічна панель, палітра команд, довідка «?», меню користувача…
    ├── pages/                     сторінки (зараз заглушки), auth/, system/, dev/
    └── styles/                    theme.css (токени), lines.css, base.css, motion.css
```

## Як додати або наповнити сторінку

1. Маршрут уже є в `src/app/routes.ts` (усі шляхи з UI.md §1 + `/admin/catalog/structure`). Відкрийте файл
   сторінки, наприклад `src/pages/public/GroupSchedulePage.tsx`, і **замініть його вміст** — залиште
   `export default function GroupSchedulePage()`. Роутер не чіпайте.
2. Новий маршрут (якщо справді потрібен) — один запис у `ROUTES`: `id`, `path`, `title`, `description`,
   `layout` (`public | teacher | admin | bare`), `roles`, `page: () => import(...)`, за потреби `nav`.
   Бічна панель, нижні вкладки, палітра й заголовок вкладки підхоплять його самі.
3. Каркас сторінки: `PageHeader` (єдиний h1) → `Section`/`Panel` → вміст. Параметри — `useParams()`,
   робоча дата адмінки — `useWorkingDate()` (синхронізована з `?date=`), стан подання — у query-параметрах.
4. Дані — хуки з `src/api/<область>.ts` (правила — `web/src/api/README.md`). Стани: `Skeleton`/`LoadingRegion`
   під час завантаження, `ErrorState` з `onRetry`, `EmptyState` з поясненням і дією.
5. Дії сторінки в палітрі: `useRegisterCommands([...])`; клавіші: `useHotkeys([{ keys, handler, description,
   group }])` — описані з'являються в довідці «?». На публічних сторінках розкладу запамʼятовуйте ціль:
   `useFavoritesStore.getState().rememberView({ type, id, label, url })` (головна відкриває останню групу).
6. Підтвердження небезпечних дій: `await confirm({ title, objectName, tone: 'danger' })`; результат —
   `notify.success(...)`, оптимістичні правки — `notify.undo(...)`.
7. Перед здачею: `npm run build && npm run lint && npm test`, і огляд сторінки на 360 та 1440 px в обох темах.

## Каталог компонентів

**Базові (`@/components/ui`):** `Button` (primary/secondary/ghost/danger/danger-ghost/shell/link, `loading`,
`asChild`), `IconButton` (обовʼязковий `label` → підказка + aria-label, `shortcut`), `Input` (`iconStart`,
`endSlot`), `NumberInput`, `Textarea`, `Select`, `Combobox` (нечіткий пошук, віддалений режим), `Checkbox`
(+ indeterminate), `Switch`, `RadioGroup`, `SegmentedControl`, `Field` (+ `useFieldControl`), `Dialog`,
`ConfirmDialog` + `confirm()`, `Drawer` (vaul), `Popover`, `HoverCard`, `HoverDetails` (наведення + клавіатура),
`Tooltip`, `Tabs`, `Badge`, `Chip`, `Plate` (табличка коду), `Panel`, `Section`, `DataTable` (сортування, липка
шапка, стани, пагінація, `RowActions`), `Pagination`, `Skeleton`/`SkeletonText`/`LoadingRegion`, `EmptyState`,
`ErrorState`, `Toaster` + `notify` (success/info/warning/error/undo/promise), `Kbd`/`KbdCombo`, `Avatar`,
`Spinner`, `DateNav` (‹ сьогодні › + календар, ←/→/T), `ScoreBar`, `ParityBadge`/`ParityMark`, `StatusDot`,
`PageHeader`, `Breadcrumbs`.

**Розклад (`@/components/schedule`):** `LessonCell` (стани REGULAR/SUBSTITUTED/CANCELLED/ADDITIONAL/MOVED/
ROOM_CHANGED/BLOCK, підгрупа, онлайн, колір лінії дисципліни, картка деталей), `LessonDetails`, `DayList`
(телефон, лінія маршруту), `DayColumn` (день колонкою), `WeekGrid` (таблиця тижня, липка шапка, час пʼятниці),
`NowIndicator` (`strip` — лінія з зупинками й відліком, `inline` — рядок). Перспектива `group | teacher | room`
визначає, що писати в клітинці.

**Оболонка (`components/shell`):** `PublicHeader`, `PublicFooter`, `BottomTabBar`, `AdminSidebar`, `AdminTopBar`,
`WorkingDateSwitcher`, `CommandPalette` (+ `CommandPaletteHost`, лінива), `ShortcutsDialog`, `UserMenu`,
`ThemeToggle`, `ThemeController`, `LiveStatus`, `SearchTrigger`, `SkipLink`, `NavigationProgress`, `Wordmark`.

## Дизайн-правила (стисло; повністю — `web/DESIGN.md`)

- Світ — **трамвайний розклад на зупинці**: емальована синя смуга, лінія маршруту з «живим» маркером,
  велика цифра пари й дрібний час, зміни як «обʼїзд» поверх розкладу з видимим закресленим оригіналом.
- Кольори — лише семантичні утиліти: `bg-canvas|surface|raised|sunken|hover|selected|shell|accent|…`,
  `text-primary|secondary|muted|accent|change|cancel|add|room|…`, `border-default|separator|control|…`.
  Палітру Tailwind вимкнено (`--color-*: initial`), тож `bg-blue-600` не працює і ловиться детектором.
- Стани: бурштин — заміна/перенесення, червоний — скасовано, зелений — додаткова, блакитний — аудиторія,
  штрихування — блок. Маршрутний синій — лише «живе й вибране» і головна дія.
- Шрифти: Fira Sans (UI) і Fira Sans Condensed (цифри пар, коди, клітинки). Розміри — ролями: `text-meta`
  12, `text-caption` 13, `text-ui` 14, `text-body` 16, `text-heading` 18, `text-title` 22, `text-headline` 28,
  `text-display` 40, `text-numeral` 56. Числа в стовпчиках — `figures`.
- Радіуси `rounded-plate` 3 / `control` 6 / `panel` 8 / `dialog` 12. Тіні лише `shadow-popover|dialog|lift`.
- Рух 100–300 мс, `ease-out`; переміщення лише `motion-safe:`; тема перемикається «жалюзі» через View
  Transitions. Іконки — lucide, 1.75 px, кожна кнопка-іконка має підпис.
- Темна тема — через `light-dark()`: будь-який блок може бути «острівцем» (`data-theme="light"` для аркуша
  розпорядження).

## Рішення (без живого обговорення — субагент)

- **Напрям дизайну** обрано за процесом impeccable (concept-seed, seed:97a6d6c7b5b3524d): «трамвайний розклад»
  (кандидат 7 зі списку), з «підняттям» дисциплін від відхилених претендентів. Контракт напряму —
  `web/.impeccable/surfaces/src-layouts-publiclayout-tsx.md`, продуктова правда — `web/PRODUCT.md` (позначені
  `inferred` факти — висновки з документів, не підтверджені відповіді).
- **Шрифт:** Sofia Sans замінено на Fira Sans — кирилиця Sofia за замовчуванням болгарська (т → m), для
  українського читача це помилка.
- **Токени по просторах імен Tailwind 4** (`--background-color-*`, `--text-color-*`, `--border-color-*`):
  утиліта сама обмежує роль (фон не потрапить у текст).
- **Деталі заняття** — `HoverDetails` (Popover): наведення мишею + Enter/тап; Radix HoverCard недоступний з клавіатури.
- **Тиждень** — справжня `<table>` (семантика для читачів екрана), день на телефоні — список з лінією.
- **Тихе оновлення токена** — refresh token (Keycloak видає його публічним клієнтам), без iframe; токен у
  `sessionStorage`. Ролі — з `realm_access.roles` access-токена (лише для інтерфейсу, перевіряє сервер).
- **`/login`** одразу веде на Keycloak; якщо він недоступний — пояснення і «Спробувати ще раз».
- **Макети кабінету й адмінки** вантажаться ліниво; палітра команд — при першому відкритті. Основний JS
  публічної частини ≈ 123 КБ gzip.
- **SSE** — одне зʼєднання на вкладку в макетах адмінки/кабінету; сторінки підписуються `useServerEvent`.
- **Головна** у майбутньому має відкривати останню групу (`useFavoritesStore.lastViewed`).

## Відхилення й припущення щодо контрактів

- Поля, яких CONTRACTS.md не описує (CRUD каталогу, `perGroup` у метриках, `alerts`, `suggestion`, журнал
  доставок, відповідь на редагування чернетки, `bestScore`), типізовано за DOMAIN.md і позначено `@inferred`.
- `ChangeView.from/to` трактовано як текстовий знімок (як рядки розпорядження), бо контракт не уточнює тип.
- `PublicMetaDto.semester` типізовано як обовʼязковий (як у контракті); якщо бекенд поверне `null` поза
  семестром — тип треба послабити і записати в `docs/CHANGELOG-CONTRACTS.md`.

## Відомі прогалини

- Сторінки — заглушки (робота агентів сторінок). PWA, ICS, QR, Web Share, друк A4 — не реалізовані (UI.md §4).
- CSP у `nginx.conf` дозволяє Keycloak лише на `http://localhost:8180`; для іншого хоста змініть
  `connect-src`/`form-action`. Резолвер nginx `127.0.0.11` працює в мережі docker compose, не в `docker run`
  за замовчуванням.
- Lighthouse не вимірювався; бекенд, Keycloak і SSE наживо не перевірялися (їх не було запущено).
- E2E запускалися на Chromium 1248 із кешу (для 1.65 потрібен 1254: `npx playwright install chromium`).
