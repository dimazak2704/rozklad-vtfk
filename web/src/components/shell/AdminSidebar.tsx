import { PanelLeftClose, PanelLeftOpen } from 'lucide-react';
import { NavLink } from 'react-router';
import { navFor, type NavMeta } from '@/app/routes';
import { cn } from '@/lib/cn';
import { IconButton } from '../ui/IconButton';
import { Tooltip } from '../ui/Tooltip';
import { Wordmark } from './Wordmark';

const SECTIONS: ReadonlyArray<NonNullable<NavMeta['section']>> = ['Щодня', 'Розклад', 'Дані', 'Система'];

interface AdminSidebarProps {
  collapsed: boolean;
  onToggleCollapsed?: () => void;
  /** Клік по пункту (закрити шторку на планшеті). */
  onNavigate?: () => void;
  className?: string;
}

/**
 * Бічна панель диспетчера — «лінія» маршрутного синього: розділи «Щодня / Розклад / Дані / Система»,
 * іконки з підписами, згортається до іконок з підказками.
 */
export function AdminSidebar({ collapsed, onToggleCollapsed, onNavigate, className }: AdminSidebarProps) {
  const items = navFor('admin');
  return (
    <div className={cn('flex h-full flex-col bg-shell text-on-shell', className)}>
      <div className={cn('flex h-14 shrink-0 items-center border-b border-shell', collapsed ? 'justify-center px-2' : 'px-4')}>
        <Wordmark to="/admin" compact={collapsed} />
      </div>
      <nav aria-label="Розділи панелі диспетчера" className="scrollbar-none min-h-0 flex-1 overflow-y-auto overscroll-contain py-3">
        {SECTIONS.map((section) => {
          const sectionItems = items.filter((item) => item.nav.section === section);
          if (sectionItems.length === 0) return null;
          return (
            <div key={section} className="mb-3 last:mb-0">
              {collapsed ? (
                <div aria-hidden="true" className="mx-4 mb-2 h-px bg-[var(--border-color-shell)] first:hidden" />
              ) : (
                <p className="px-5 pb-1 text-caption font-semibold text-on-shell-muted">{section}</p>
              )}
              <ul className={cn('flex flex-col gap-0.5', collapsed ? 'px-2' : 'px-2.5')}>
                {sectionItems.map((item) => {
                  const link = (
                    <NavLink
                      to={item.path}
                      end={item.path === '/admin'}
                      onClick={onNavigate}
                      aria-label={collapsed ? item.nav.label : undefined}
                      className={({ isActive }) =>
                        cn(
                          'flex h-9 items-center gap-3 rounded-control text-ui transition-[background-color,color] duration-100',
                          'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus-on-shell',
                          collapsed ? 'justify-center px-0' : 'px-2.5',
                          isActive
                            ? 'bg-shell-raised font-semibold text-on-shell'
                            : 'text-on-shell-muted hover:bg-shell-hover hover:text-on-shell',
                        )
                      }
                    >
                      {({ isActive }) => (
                        <>
                          <item.nav.icon aria-hidden="true" className="size-[1.125rem]" />
                          {!collapsed ? <span className="truncate">{item.nav.label}</span> : null}
                          {isActive && !collapsed ? (
                            <span aria-hidden="true" className="ms-auto size-1.5 rounded-full bg-[var(--text-color-on-shell)]" />
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
      {onToggleCollapsed ? (
        <div className={cn('flex shrink-0 border-t border-shell p-2', collapsed ? 'justify-center' : 'justify-end')}>
          <IconButton
            label={collapsed ? 'Розгорнути панель' : 'Згорнути панель'}
            icon={
              collapsed ? (
                <PanelLeftOpen aria-hidden="true" className="size-[1.125rem] rtl:rotate-180" />
              ) : (
                <PanelLeftClose aria-hidden="true" className="size-[1.125rem] rtl:rotate-180" />
              )
            }
            variant="shell"
            tooltipSide="right"
            onClick={onToggleCollapsed}
          />
        </div>
      ) : null}
    </div>
  );
}
