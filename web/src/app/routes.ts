/**
 * Центральний реєстр маршрутів (UI.md §1). Роутер, бічна панель адмінки, нижні вкладки,
 * командна палітра й заголовки вкладок браузера будуються саме звідси.
 *
 * Сторінка = файл у src/pages/... з `export default function …Page()`. Агенти сторінок змінюють
 * лише вміст своїх файлів; новий маршрут — один запис тут.
 */
import {
  ArrowLeftRight,
  BookOpen,
  Building,
  Calculator,
  CalendarCog,
  CalendarDays,
  CalendarHeart,
  CalendarX,
  Clock3,
  DoorOpen,
  FileText,
  Info,
  LayoutDashboard,
  Layers,
  Puzzle,
  Send,
  Settings,
  Table2,
  Upload,
  UserRound,
  Users,
  type LucideIcon,
} from 'lucide-react';
import type { ComponentType } from 'react';
import type { Role } from '@/api/types';

export type LayoutKind = 'public' | 'teacher' | 'admin' | 'bare';

export interface NavMeta {
  /** Підпис у меню (може бути коротшим за заголовок сторінки). */
  label: string;
  icon: LucideIcon;
  /** Розділ бічної панелі адмінки. */
  section?: 'Щодня' | 'Розклад' | 'Дані' | 'Система';
  order: number;
  /** Показувати в нижніх вкладках на телефоні (публічна частина, кабінет). */
  tab?: boolean;
  /** Показувати в навігації шапки (десктоп). */
  header?: boolean;
  keywords?: string[];
}

export interface AppRoute {
  id: string;
  path: string;
  /** Заголовок вкладки браузера й назва в палітрі. */
  title: string;
  /** Одним реченням: що буде на сторінці (заглушки й підказки палітри). */
  description: string;
  layout: LayoutKind;
  /** Хто має доступ. Без ролей — усі. */
  roles?: readonly Role[];
  page: () => Promise<{ default: ComponentType }>;
  nav?: NavMeta;
  /** Лише в `npm run dev`. */
  devOnly?: boolean;
}

const TEACHER_ROLES: readonly Role[] = ['TEACHER', 'ADMIN'];
const ADMIN_ROLES: readonly Role[] = ['ADMIN'];

export const ROUTES: readonly AppRoute[] = [
  // ── Публічна частина ──
  {
    id: 'home',
    path: '/',
    title: 'Розклад',
    description: 'Пошук групи, викладача чи аудиторії та розклад останньої переглянутої групи на сьогодні.',
    layout: 'public',
    page: () => import('@/pages/public/HomePage'),
    nav: { label: 'Розклад', icon: CalendarDays, order: 1, tab: true, header: true },
  },
  {
    id: 'group',
    path: '/g/:code',
    title: 'Розклад групи',
    description: 'День і тиждень групи з виділеними змінами, «зараз» і поділитися посиланням.',
    layout: 'public',
    page: () => import('@/pages/public/GroupSchedulePage'),
  },
  {
    id: 'teacher',
    path: '/t/:id',
    title: 'Розклад викладача',
    description: 'Пари викладача на день і тиждень (публічні короткі імена).',
    layout: 'public',
    page: () => import('@/pages/public/TeacherSchedulePage'),
  },
  {
    id: 'room',
    path: '/r/:id',
    title: 'Розклад аудиторії',
    description: 'Зайнятість аудиторії на день і тиждень.',
    layout: 'public',
    page: () => import('@/pages/public/RoomSchedulePage'),
  },
  {
    id: 'changes',
    path: '/changes',
    title: 'Заміни',
    description: 'Заміни на сьогодні, завтра чи будь-яку дату, згруповані за групами, з фільтром.',
    layout: 'public',
    page: () => import('@/pages/public/ChangesPage'),
    nav: { label: 'Заміни', icon: ArrowLeftRight, order: 2, tab: true, header: true, keywords: ['зміни', 'розпорядження'] },
  },
  {
    id: 'bells',
    path: '/bells',
    title: 'Дзвінки',
    description: 'Розклад дзвінків профілю, що діє; сьогоднішній день виділено.',
    layout: 'public',
    page: () => import('@/pages/public/BellsPage'),
    nav: { label: 'Дзвінки', icon: Clock3, order: 3, tab: true, header: true, keywords: ['пари', 'час'] },
  },
  {
    id: 'about',
    path: '/about',
    title: 'Про систему',
    description: 'Що вміє система, як читати розклад і як підписатися на Telegram-бота.',
    layout: 'public',
    page: () => import('@/pages/public/AboutPage'),
    // у шапці лише три розділи; «Про систему» — у підвалі й палітрі команд
    nav: { label: 'Про систему', icon: Info, order: 4, keywords: ['бот', 'telegram'] },
  },
  {
    id: 'invite',
    path: '/invite/:token',
    title: 'Реєстрація викладача',
    description: 'Створення облікового запису за запрошенням: логін і пароль.',
    layout: 'public',
    page: () => import('@/pages/public/InvitePage'),
  },
  {
    id: 'login',
    path: '/login',
    title: 'Вхід',
    description: 'Вхід для викладачів і диспетчерів через обліковий запис коледжу.',
    layout: 'public',
    page: () => import('@/pages/auth/LoginPage'),
  },
  {
    id: 'auth-callback',
    path: '/auth/callback',
    title: 'Вхід',
    description: 'Завершення входу після Keycloak.',
    layout: 'bare',
    page: () => import('@/pages/auth/AuthCallbackPage'),
  },

  // ── Кабінет викладача ──
  {
    id: 'me',
    path: '/me',
    title: 'Мій розклад',
    description: 'Мій день і тиждень, заміни на підтвердження, повідомлення про відсутність, Telegram.',
    layout: 'teacher',
    roles: TEACHER_ROLES,
    page: () => import('@/pages/teacher/MyDayPage'),
    nav: { label: 'Мій розклад', icon: CalendarDays, order: 1, tab: true, header: true },
  },
  {
    id: 'me-absences',
    path: '/me/absences',
    title: 'Мої відсутності',
    description: 'Повідомлені відсутності; нові можна додати на сьогодні чи майбутні дати.',
    layout: 'teacher',
    roles: TEACHER_ROLES,
    page: () => import('@/pages/teacher/MyAbsencesPage'),
    nav: { label: 'Відсутності', icon: CalendarX, order: 2, tab: true, header: true, keywords: ['лікарняний', 'відпустка'] },
  },
  {
    id: 'me-preferences',
    path: '/me/preferences',
    title: 'Побажання до розкладу',
    description: 'Сітка небажаних і неможливих пар для генератора розкладу.',
    layout: 'teacher',
    roles: TEACHER_ROLES,
    page: () => import('@/pages/teacher/MyPreferencesPage'),
    nav: { label: 'Побажання', icon: CalendarHeart, order: 3, tab: true, header: true },
  },

  // ── Панель диспетчера ──
  {
    id: 'admin',
    path: '/admin',
    title: 'Панель диспетчера',
    description: 'Сьогодні й завтра: відсутності, непокриті пари, підтвердження, чернетка розпорядження.',
    layout: 'admin',
    roles: ADMIN_ROLES,
    page: () => import('@/pages/admin/DashboardPage'),
    nav: { label: 'Панель', icon: LayoutDashboard, section: 'Щодня', order: 1 },
  },
  {
    id: 'admin-changes',
    path: '/admin/changes',
    title: 'Заміни',
    description: 'Відсутності → зачеплені пари → варіанти з оцінками → підтвердження викладачів.',
    layout: 'admin',
    roles: ADMIN_ROLES,
    page: () => import('@/pages/admin/ChangesPage'),
    nav: { label: 'Заміни', icon: ArrowLeftRight, section: 'Щодня', order: 2, keywords: ['відсутність', 'кандидати'] },
  },
  {
    id: 'admin-orders',
    path: '/admin/orders',
    title: 'Розпорядження',
    description: 'Чернетки за датами, перегляд у форматі коледжу, публікація, доповнення, архів.',
    layout: 'admin',
    roles: ADMIN_ROLES,
    page: () => import('@/pages/admin/OrdersPage'),
    nav: { label: 'Розпорядження', icon: FileText, section: 'Щодня', order: 3, keywords: ['наказ', 'публікація'] },
  },
  {
    id: 'admin-timetable',
    path: '/admin/timetable',
    title: 'Базовий розклад',
    description: 'Редактор сітки з перетягуванням, конфлікти, версії, порівняння, публікація.',
    layout: 'admin',
    roles: ADMIN_ROLES,
    page: () => import('@/pages/admin/TimetablePage'),
    nav: { label: 'Базовий розклад', icon: Table2, section: 'Розклад', order: 4, keywords: ['версія', 'сітка'] },
  },
  {
    id: 'admin-generation',
    path: '/admin/generation',
    title: 'Генератор розкладу',
    description: 'Параметри, хід розвʼязання наживо, метрики «було / стало», застосування як чернетки.',
    layout: 'admin',
    roles: ADMIN_ROLES,
    page: () => import('@/pages/admin/GenerationPage'),
    nav: { label: 'Генератор', icon: Puzzle, section: 'Розклад', order: 5, keywords: ['timefold', 'оптимізація'] },
  },
  {
    id: 'admin-load',
    path: '/admin/load',
    title: 'Навантаження',
    description: 'Години на тиждень за групами й дисциплінами з тижневим шаблоном.',
    layout: 'admin',
    roles: ADMIN_ROLES,
    page: () => import('@/pages/admin/LoadPage'),
    nav: { label: 'Навантаження', icon: Layers, section: 'Розклад', order: 6 },
  },
  {
    id: 'admin-accounting',
    path: '/admin/accounting',
    title: 'Облік годин',
    description: 'План, факт, прогноз; «вичитано» з діями в один клік; статистика замін викладачів.',
    layout: 'admin',
    roles: ADMIN_ROLES,
    page: () => import('@/pages/admin/AccountingPage'),
    nav: { label: 'Облік годин', icon: Calculator, section: 'Розклад', order: 7, keywords: ['вичитано', 'план', 'факт'] },
  },
  {
    id: 'admin-import',
    path: '/admin/import',
    title: 'Імпорт',
    description: 'Завантаження Excel, DOCX, PDF і фото; перевірка чернетки; зіставлення назв; застосування.',
    layout: 'admin',
    roles: ADMIN_ROLES,
    page: () => import('@/pages/admin/ImportPage'),
    nav: { label: 'Імпорт', icon: Upload, section: 'Дані', order: 8, keywords: ['excel', 'файл'] },
  },
  {
    id: 'admin-catalog-groups',
    path: '/admin/catalog/groups',
    title: 'Групи',
    description: 'Групи й підгрупи: пошук, фільтр, сортування, форма.',
    layout: 'admin',
    roles: ADMIN_ROLES,
    page: () => import('@/pages/admin/catalog/GroupsPage'),
    nav: { label: 'Групи', icon: Users, section: 'Дані', order: 9, keywords: ['каталог'] },
  },
  {
    id: 'admin-catalog-teachers',
    path: '/admin/catalog/teachers',
    title: 'Викладачі',
    description: 'Викладачі, псевдоніми, дисципліни, запрошення до системи.',
    layout: 'admin',
    roles: ADMIN_ROLES,
    page: () => import('@/pages/admin/catalog/TeachersPage'),
    nav: { label: 'Викладачі', icon: UserRound, section: 'Дані', order: 10, keywords: ['каталог', 'запрошення'] },
  },
  {
    id: 'admin-catalog-rooms',
    path: '/admin/catalog/rooms',
    title: 'Аудиторії',
    description: 'Аудиторії й корпуси: тип, місткість, одночасні заняття.',
    layout: 'admin',
    roles: ADMIN_ROLES,
    page: () => import('@/pages/admin/catalog/RoomsPage'),
    nav: { label: 'Аудиторії', icon: DoorOpen, section: 'Дані', order: 11, keywords: ['каталог'] },
  },
  {
    id: 'admin-catalog-disciplines',
    path: '/admin/catalog/disciplines',
    title: 'Дисципліни',
    description: 'Дисципліни з короткими назвами, псевдонімами й кольором лінії.',
    layout: 'admin',
    roles: ADMIN_ROLES,
    page: () => import('@/pages/admin/catalog/DisciplinesPage'),
    nav: { label: 'Дисципліни', icon: BookOpen, section: 'Дані', order: 12, keywords: ['каталог', 'псевдоніми'] },
  },
  {
    id: 'admin-catalog-structure',
    path: '/admin/catalog/structure',
    title: 'Структура коледжу',
    description: 'Відділення, спеціальності, циклові комісії, корпуси.',
    layout: 'admin',
    roles: ADMIN_ROLES,
    page: () => import('@/pages/admin/catalog/StructurePage'),
    nav: { label: 'Структура', icon: Building, section: 'Дані', order: 13, keywords: ['відділення', 'спеціальності', 'комісії'] },
  },
  {
    id: 'admin-calendar',
    path: '/admin/calendar',
    title: 'Календар',
    description: 'Семестри, чисельник/знаменник, профілі дзвінків, канікули, практика, режими навчання.',
    layout: 'admin',
    roles: ADMIN_ROLES,
    page: () => import('@/pages/admin/CalendarPage'),
    nav: { label: 'Календар', icon: CalendarCog, section: 'Дані', order: 14, keywords: ['семестр', 'дзвінки', 'канікули'] },
  },
  {
    id: 'admin-telegram',
    path: '/admin/telegram',
    title: 'Telegram',
    description: 'Стан бота, привʼязані чати, коди привʼязки, журнал розсилок.',
    layout: 'admin',
    roles: ADMIN_ROLES,
    page: () => import('@/pages/admin/TelegramPage'),
    nav: { label: 'Telegram', icon: Send, section: 'Система', order: 15, keywords: ['бот', 'розсилка'] },
  },
  {
    id: 'admin-settings',
    path: '/admin/settings',
    title: 'Налаштування',
    description: 'Режим погодження, підписант, нумерація, модель ШІ й бюджет, видимість, години на пару.',
    layout: 'admin',
    roles: ADMIN_ROLES,
    page: () => import('@/pages/admin/SettingsPage'),
    nav: { label: 'Налаштування', icon: Settings, section: 'Система', order: 16 },
  },

];

/**
 * Сторінка компонентів існує лише в `npm run dev`: у production-збірці `import.meta.env.DEV` — false,
 * і динамічний імпорт разом з усім кодом сторінки вирізається.
 */
const DEV_ROUTES: readonly AppRoute[] = import.meta.env.DEV
  ? [
      {
        id: 'kitchen-sink',
        path: '/dev/kitchen-sink',
        title: 'Компоненти',
        description: 'Усі компоненти дизайн-системи в обох темах.',
        layout: 'public',
        page: () => import('@/pages/dev/KitchenSinkPage'),
        devOnly: true,
      },
    ]
  : [];

/** Маршрути, доступні в поточній збірці (dev-сторінки — лише в `npm run dev`). */
export const ACTIVE_ROUTES: readonly AppRoute[] = [...ROUTES, ...DEV_ROUTES];

export function routesFor(layout: LayoutKind): AppRoute[] {
  return ACTIVE_ROUTES.filter((route) => route.layout === layout);
}

export function navFor(layout: LayoutKind): Array<AppRoute & { nav: NavMeta }> {
  return ACTIVE_ROUTES.filter((route): route is AppRoute & { nav: NavMeta } => route.layout === layout && route.nav !== undefined).sort(
    (a, b) => a.nav.order - b.nav.order,
  );
}

export function routeById(id: string): AppRoute | undefined {
  return ACTIVE_ROUTES.find((route) => route.id === id);
}

/** Шлях до розкладу групи/викладача/аудиторії. */
export const paths = {
  group: (code: string) => `/g/${encodeURIComponent(code)}`,
  teacher: (id: string) => `/t/${encodeURIComponent(id)}`,
  room: (id: string) => `/r/${encodeURIComponent(id)}`,
  login: (returnTo?: string) => (returnTo ? `/login?returnTo=${encodeURIComponent(returnTo)}` : '/login'),
} as const;
