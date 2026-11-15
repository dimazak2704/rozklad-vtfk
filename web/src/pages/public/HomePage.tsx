import { buildUrl } from '@/api/client';
import { isApiError } from '@/api/errors';
import { useBells, usePublicMeta } from '@/api/meta';
import { publicSchedulePath, usePublicChanges, usePublicGroups, usePublicSchedule } from '@/api/public';
import { GroupsDirectory } from '@/components/public/home/GroupsDirectory';
import { LastViewedDay } from '@/components/public/home/LastViewedDay';
import { NowPanel } from '@/components/public/home/NowPanel';
import { nowPanelRange } from '@/components/public/home/now-range';
import { StallNote } from '@/components/public/home/StallNote';
import { ScheduleFinder } from '@/components/public/home/ScheduleFinder';
import { TodayChanges } from '@/components/public/home/TodayChanges';
import { useHomeDay } from '@/components/public/home/useHomeDay';
import { useRememberedGroup } from '@/components/public/home/useRememberedGroup';
import { useRepairSavedTargets } from '@/components/public/home/useRepairSavedTargets';
import { useDocumentTitle } from '@/components/public/useDocumentTitle';
import { PageErrorScope } from '@/components/ui';

/** Де React Router тримає відступи прокрутки сторінок (типове імʼя ScrollRestoration). */
const SCROLL_POSITIONS_KEY = 'react-router-scroll-positions';

/**
 * Свіже відкриття «/» (перезавантаження, посилання, нова вкладка) починається згори. Інакше ScrollRestoration
 * повертає старий відступ, а головна тим часом стала коротшою (курси згорнуті, «4КІ1 сьогодні» на місці
 * списку) — і відлік ховається під шапкою. «Назад» усередині застосунку відступ зберігає.
 *
 * Працює під час завантаження модуля: сторінки маршрутизатора ліниві, тож модуль головної виконується до
 * першого рендеру, а отже — до того, як ScrollRestoration прочитає збережені відступи.
 */
function forgetHomeScrollOnFreshLoad() {
  try {
    const entry = performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming | undefined;
    if (!entry || entry.type === 'back_forward') return;
    if (new URL(entry.name).pathname !== '/' || window.location.pathname !== '/') return;
    const raw = sessionStorage.getItem(SCROLL_POSITIONS_KEY);
    if (!raw) return;
    const positions = JSON.parse(raw) as Record<string, number>;
    const key = (window.history.state as { key?: string } | null)?.key ?? 'default';
    sessionStorage.setItem(SCROLL_POSITIONS_KEY, JSON.stringify(Object.fromEntries(Object.entries(positions).filter(([k]) => k !== key))));
  } catch {
    // без sessionStorage відступів немає й відновлювати нічого
  }
}

forgetHomeScrollOnFreshLoad();

/**
 * Головна — спокійний особистий календар: спершу «що зараз» (відлік і доріжка дня), потім пошук
 * розкладу, своя група на сьогодні, заміни одним реченням і всі групи під розкриттям.
 * Перший візит (своєї групи ще немає): під пошуком — курси згорнутими рядками, праворуч — лише «Заміни».
 * Якщо сервер недоступний — одна помилка на сторінку з однією кнопкою, а не чотири однакові;
 * без мережі чи коли сервер довго мовчить — один спокійний рядок замість нескінченних силуетів.
 */
export default function HomePage() {
  const meta = usePublicMeta();
  const home = useHomeDay();
  const group = useRememberedGroup();
  // ті самі запити, що й у блоках (React Query їх обʼєднує): потрібні лише для спільної помилки
  const bells = useBells(meta.data?.studyDay ? meta.data.today : null);
  const groups = usePublicGroups();
  const changes = usePublicChanges(home.date);
  const day = usePublicSchedule('GROUP', group?.id, home.date, home.date, { enabled: Boolean(group) });
  // збережена група зникла (перевантаження бази): це не збій сторінки, а застаріла закладка — довідник
  // перенесе її на новий id або забуде, а сира відповідь сервера з id на головну не потрапляє
  const staleGroup = isApiError(day.error) && day.error.isNotFound && !day.error.isHistoryNotPublic;
  useRepairSavedTargets();
  const queries = [meta, bells, groups, changes, group && !staleGroup ? day : null];
  // що з цього могло прийти зі збереженої копії Service Worker (адреси — як у клієнта API)
  const range = nowPanelRange(meta.data);
  const savedUrls = [
    buildUrl('/public/meta'),
    buildUrl('/public/changes', { date: home.date }),
    group ? buildUrl(publicSchedulePath('GROUP', group.id), { from: home.date, to: home.date }) : undefined,
    group && range ? buildUrl(publicSchedulePath('GROUP', group.id), range) : undefined,
  ];

  useDocumentTitle('Розклад ВТФК');

  return (
    <PageErrorScope queries={queries} title="Розклад зараз не завантажується" className="gap-8 md:gap-12">
      <StallNote queries={queries} savedUrls={savedUrls} />
      <NowPanel />

      <div className="grid items-start gap-x-12 gap-y-8 md:grid-cols-[minmax(0,1fr)_16rem] lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
        <div className="flex min-w-0 flex-col gap-8 md:gap-10">
          <section aria-labelledby="find-heading" className="flex flex-col gap-3">
            <h1 id="find-heading" className="font-display text-title font-semibold text-primary">
              Знайти розклад
            </h1>
            <ScheduleFinder autoFocus />
          </section>
          {group ? <LastViewedDay /> : <GroupsDirectory />}
        </div>

        <aside aria-label={group ? 'Заміни й групи' : 'Заміни'} className="flex min-w-0 flex-col gap-8 md:gap-10">
          <TodayChanges />
          {group ? <GroupsDirectory /> : null}
        </aside>
      </div>
    </PageErrorScope>
  );
}
