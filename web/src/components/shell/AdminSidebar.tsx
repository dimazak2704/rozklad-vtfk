import { PanelLeftClose, PanelLeftOpen } from 'lucide-react';
import { NavLink } from 'react-router';
import { navFor, type NavMeta } from '@/app/routes';
import { cn } from '@/lib/cn';
import { IconButton } from '../ui/IconButton';
import { Tooltip } from '../ui/Tooltip';
import { Wordmark } from './Wordmark';

const SECTIONS: ReadonlyArray<NonNullable<NavMeta['section']>> = ['Щодня', 'Розклад', 'Дані', 'Система'];

/** Лічильник біля пункту: скільки записів чекає; `unresolved` — є невирішені (тоді синім). */
export interface SidebarCount {
  value: number;
  unresolved?: boolean;
}

interface AdminSidebarProps {
  collapsed: boolean;
  onToggleCollapsed?: () => void;
  /** Клік по пункту (закрити шторку на планшеті). */
  onNavigate?: () => void;
  /** Живі лічильники за id маршруту: `{ 'admin-changes': { value: 3, unresolved: true } }`. */
  counts?: Readonly<Record<string, SidebarCount | undefined>>;
  className?: string;
}

/**
 * Бічна панель диспетчера — спокійна біла панель (як Outlook чи Google Admin): розділи
 * «Щодня / Розклад / Дані / Система», іконки з підписами, згортається до іконок з підказками.
 * Емаль тут лише в табличці «ВТФК»; вибраний пункт — лише тонування, синій текст і вага 600 (без окремої риски).
 */
export function AdminSidebar({ collapsed, onToggleCollapsed, onNavigate, counts, className }: AdminSidebarProps) {
  const items = navFor('admin');
  return (
    <div className={cn('flex h-full flex-col border-e border-default bg-surface text-primary', className)}>
      <div
        className={cn(
          'flex h-14 shrink-0 items-center gap-2 border-b border-default',
          collapsed ? 'flex-col justify-center px-2' : 'ps-4 pe-2',
        )}
      >
        {collapsed ? null : <Wordmark to="/admin" tone="surface" />}
        {onToggleCollapsed ? (
          <IconButton
            label={collapsed ? 'Розгорнути панель' : 'Згорнути панель'}
            icon={
              collapsed ? (
                <PanelLeftOpen aria-hidden="true" className="size-[1.125rem] rtl:rotate-180" />
              ) : (
                <PanelLeftClose aria-hidden="true" className="size-[1.125rem] rtl:rotate-180" />
              )
            }
            size="sm"
            tooltipSide="right"
            className={collapsed ? undefined : 'ms-auto'}
            onClick={onToggleCollapsed}
          />
        ) : null}
      </div>
      <nav aria-label="Розділи панелі диспетчера" className="scrollbar-none min-h-0 flex-1 overflow-y-auto overscroll-contain px-3 py-3">
        {SECTIONS.map((section, sectionIndex) => {
          const sectionItems = items.filter((item) => item.nav.section === section);
          if (sectionItems.length === 0) return null;
          return (
            <div key={section}>
              {collapsed ? (
                sectionIndex > 0 ? <div aria-hidden="true" className="mx-2 my-3 h-px bg-[var(--border-color-separator)]" /> : null
              ) : (
                <p className={cn('px-3 pb-1 text-meta font-semibold text-muted', sectionIndex > 0 && 'pt-4')}>{section}</p>
              )}
              <ul className="flex flex-col gap-1">
                {sectionItems.map((item) => {
                  const count = counts?.[item.id];
                  const link = (
                    <NavLink
                      to={item.path}
                      end={item.path === '/admin'}
                      onClick={onNavigate}
                      aria-label={collapsed ? item.nav.label : undefined}
                      className={({ isActive }) =>
                        cn(
                          'group/item relative flex h-9 items-center gap-3 rounded-control text-ui transition-[background-color,color] duration-100',
                          'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus pointer-coarse:h-11',
                          collapsed ? 'justify-center px-0' : 'px-3',
                          isActive
                            ? 'bg-selected font-semibold text-accent'
                            : 'font-normal text-secondary hover:bg-hover hover:text-primary active:bg-active',
                        )
                      }
                    >
                      {({ isActive }) => (
                        <>
                          <item.nav.icon
                            aria-hidden="true"
                            className={cn('size-[1.125rem]', isActive ? 'text-accent' : 'text-muted group-hover/item:text-secondary')}
                          />
                          {!collapsed ? <span className="truncate">{item.nav.label}</span> : null}
                          {!collapsed && count && count.value > 0 ? (
                            <span
                              className={cn(
                                'ms-auto text-meta figures',
                                count.unresolved ? 'font-semibold text-accent' : 'font-normal text-muted',
                              )}
                            >
                              {count.value}
                            </span>
                          ) : null}
                        </>
                      )}
                    </NavLink>
                  );
                  return (
                    <li key={item.id}>
                      {collapsed ? (
                        <Tooltip content={item.nav.label} side="right">
                          {link}
                        </Tooltip>
                      ) : (
                        link
                      )}
                    </li>
                  );
                })}
              </ul>
            </div>
          );
        })}
      </nav>
    </div>
  );
}
