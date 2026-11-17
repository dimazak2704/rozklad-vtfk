import { ChevronDown, PanelLeftClose, PanelLeftOpen } from 'lucide-react';
import { useState } from 'react';
import { Link, useLocation, useMatch } from 'react-router';
import { navFor, type AppRoute, type NavMeta } from '@/app/routes';
import { cn } from '@/lib/cn';
import { IconButton } from '../ui/IconButton';
import { Tooltip } from '../ui/Tooltip';
import { UserMenu } from './UserMenu';
import { Wordmark } from './Wordmark';

/** Позначка біля пункту: є щось, що чекає рішення (одна крапка, без чисел). */
export interface SidebarCount {
  value: number;
  unresolved?: boolean;
}

interface AdminSidebarProps {
  collapsed: boolean;
  onToggleCollapsed?: () => void;
  /** Клік по пункту (закрити шторку на планшеті). */
  onNavigate?: () => void;
  /** Що чекає рішення за id маршруту: `{ 'admin-changes': { value: 3, unresolved: true } }` — показується крапкою. */
  counts?: Readonly<Record<string, SidebarCount | undefined>>;
  /** Блок користувача внизу (на десктопі; у шторці він у верхній панелі). */
  showUser?: boolean;
  className?: string;
}

const PLANNING_KEY = 'rozklad.sidebar.planning';

function readPlanningOpen(): boolean {
  try {
    return localStorage.getItem(PLANNING_KEY) !== 'closed';
  } catch {
    return true;
  }
}

type Item = AppRoute & { nav: NavMeta };

function NavItem({
  item,
  collapsed,
  count,
  onNavigate,
}: {
  item: Item;
  collapsed: boolean;
  count?: SidebarCount;
  onNavigate?: () => void;
}) {
  const { pathname } = useLocation();
  const label = item.nav.sidebarLabel ?? item.nav.label;
  const prefixActive = item.nav.activePrefix ? pathname.startsWith(item.nav.activePrefix) : false;
  // «Сьогодні» (/admin) — лише точний збіг, інші — і їхні підсторінки (як `end` у NavLink)
  const routeActive = Boolean(useMatch({ path: item.path, end: item.path === '/admin' }));
  const active = routeActive || prefixActive;
  const needsDecision = Boolean(count?.unresolved && count.value > 0);
  // клас — рядком, а не функцією NavLink: у згорнутій рейці посилання стоїть у Tooltip (Radix Slot), а Slot
  // склеює className як рядки — функція губилася, і з нею центрування, тло й підсвітка вибраного пункту
  const link = (
    <Link
      to={item.path}
      onClick={onNavigate}
      // підсвічений розділ («Довідники» на сторінці викладачів) читач екрана теж чує як поточний: це не сама
      // сторінка посилання, тож `true`, а не `page`
      aria-current={routeActive ? 'page' : prefixActive ? 'true' : undefined}
      aria-label={collapsed ? (needsDecision ? `${label}: потрібне рішення` : label) : undefined}
      className={cn(
        'group/item relative flex h-9 items-center gap-3 rounded-control text-ui transition-[background-color,color] duration-150',
        'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus pointer-coarse:h-11',
        collapsed ? 'justify-center px-0' : 'px-3',
        active ? 'bg-selected font-[650] text-primary' : 'font-[550] text-secondary hover:bg-hover hover:text-primary active:bg-active',
      )}
    >
      <item.nav.icon aria-hidden="true" className={cn('size-4 shrink-0', active ? 'text-accent' : 'text-muted group-hover/item:text-secondary')} />
      {!collapsed ? <span className="truncate">{label}</span> : null}
      {needsDecision ? (
        <span
          aria-hidden={collapsed || undefined}
          className={cn('size-2 shrink-0 rounded-full bg-change', collapsed ? 'absolute end-1.5 top-1.5' : 'ms-auto')}
        >
          {!collapsed ? <span className="sr-only">потрібне рішення</span> : null}
        </span>
      ) : null}
    </Link>
  );
  return (
    <li>
      {collapsed ? (
        <Tooltip content={label} side="right">
          {link}
        </Tooltip>
      ) : (
        link
      )}
    </li>
  );
}

/**
 * Бічна панель диспетчера на полотні, відділена тонкою лінією: щоденні інструменти («Сьогодні, Заміни,
 * Розпорядження, Розклад») і група «Планування», що згортається (стан запамʼятовується). Вибраний пункт —
 * тонування й вага, без лічильників; лише крапка біля «Замін», коли щось чекає рішення. Унизу — користувач.
 */
export function AdminSidebar({ collapsed, onToggleCollapsed, onNavigate, counts, showUser = false, className }: AdminSidebarProps) {
  const items = navFor('admin').filter((item) => item.nav.sidebar !== false);
  const daily = items.filter((item) => item.nav.section !== 'Планування');
  const planning = items.filter((item) => item.nav.section === 'Планування');
  const { pathname } = useLocation();
  const [planningOpen, setPlanningOpen] = useState(readPlanningOpen);
  // поточна сторінка всередині групи — групу видно завжди, щоб людина бачила, де вона
  const planningActive = planning.some((item) =>
    item.nav.activePrefix ? pathname.startsWith(item.nav.activePrefix) : pathname.startsWith(item.path),
  );
  const showPlanning = collapsed || planningOpen || planningActive;

  const togglePlanning = () => {
    const next = !planningOpen;
    setPlanningOpen(next);
    try {
      localStorage.setItem(PLANNING_KEY, next ? 'open' : 'closed');
    } catch {
      /* приватний режим: просто не запамʼятовуємо */
    }
  };

  return (
    <div className={cn('flex h-full flex-col border-e border-separator bg-canvas text-primary', className)}>
      <div className={cn('flex h-16 shrink-0 items-center gap-2', collapsed ? 'justify-center px-2' : 'ps-5 pe-2')}>
        {collapsed ? <Wordmark to="/admin" compact tooltipSide="right" /> : <Wordmark to="/admin" />}
        {onToggleCollapsed && !collapsed ? (
          <IconButton
            label="Згорнути панель"
            icon={<PanelLeftClose aria-hidden="true" className="size-[1.125rem] rtl:rotate-180" />}
            tooltipSide="right"
            className="ms-auto"
            onClick={onToggleCollapsed}
          />
        ) : null}
      </div>
      <nav aria-label="Розділи панелі диспетчера" className="scrollbar-none min-h-0 flex-1 overflow-y-auto overscroll-contain px-3 pt-2 pb-3">
        <ul className="flex flex-col gap-0.5">
          {daily.map((item) => (
            <NavItem key={item.id} item={item} collapsed={collapsed} count={counts?.[item.id]} onNavigate={onNavigate} />
          ))}
        </ul>
        {planning.length > 0 ? (
          <div className="mt-5">
            {collapsed ? (
              <div aria-hidden="true" className="mx-2 mb-3 h-px bg-[var(--border-color-separator)]" />
            ) : (
              <button
                type="button"
                aria-expanded={showPlanning}
                aria-controls="sidebar-planning"
                disabled={planningActive}
                onClick={togglePlanning}
                className={cn(
                  'flex h-8 w-full items-center gap-2 rounded-control px-3 text-caption font-[650] text-muted transition-colors duration-150',
                  'enabled:hover:text-primary focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus',
                  'pointer-coarse:h-11 disabled:cursor-default',
                )}
              >
                Планування
                {!planningActive ? (
                  <ChevronDown
                    aria-hidden="true"
                    className={cn('ms-auto size-4 transition-transform duration-200', !showPlanning && '-rotate-90 rtl:rotate-90')}
                  />
                ) : null}
              </button>
            )}
            {showPlanning ? (
              <ul id="sidebar-planning" className="mt-0.5 flex flex-col gap-0.5">
                {planning.map((item) => (
                  <NavItem key={item.id} item={item} collapsed={collapsed} count={counts?.[item.id]} onNavigate={onNavigate} />
                ))}
              </ul>
            ) : null}
          </div>
        ) : null}
      </nav>
      {showUser || (collapsed && onToggleCollapsed) ? (
        <div className={cn('flex shrink-0 items-center gap-2 border-t border-separator py-3', collapsed ? 'flex-col px-2' : 'px-3')}>
          {showUser ? <UserMenu variant={collapsed ? 'avatar' : 'block'} /> : null}
          {collapsed && onToggleCollapsed ? (
            <IconButton
              label="Розгорнути панель"
              icon={<PanelLeftOpen aria-hidden="true" className="size-[1.125rem] rtl:rotate-180" />}
              tooltipSide="right"
              onClick={onToggleCollapsed}
            />
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
