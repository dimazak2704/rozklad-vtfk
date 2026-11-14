import { History, Menu, Search } from 'lucide-react';
import { useLocation } from 'react-router';
import type { EventStreamState } from '@/api/useEventStream';
import { routeForPath } from '@/hooks/useBackLink';
import { cn } from '@/lib/cn';
import { useJournal } from '@/lib/undo/journal';
import { useCommandStore } from '@/stores/commands';
import { IconButton } from '../ui/IconButton';
import { LiveStatus } from './LiveStatus';
import { StandTimeBadge } from './StandClock';
import { UserMenu } from './UserMenu';
import { Wordmark } from './Wordmark';
import { WorkingDateSwitcher } from './WorkingDateSwitcher';

interface AdminTopBarProps {
  live: EventStreamState;
  onOpenMenu: () => void;
}

/**
 * Верхня панель диспетчера — робоча дата (лише на сторінках, що її читають: Сьогодні, Заміни, Розпорядження),
 * стан звʼязку (лише коли його немає), журнал дій і пошук іконками. На інших сторінках дата нічого б не змінювала,
 * тож її немає: людина не гадає, що робить цей перемикач.
 * Користувач — унизу бічної панелі (і шторки на телефоні); на планшеті — тут, аватаром.
 */
export function AdminTopBar({ live, onOpenMenu }: AdminTopBarProps) {
  const openPalette = useCommandStore((state) => state.setOpen);
  const openJournal = useJournal((state) => state.setPanelOpen);
  const { pathname } = useLocation();
  const dated = routeForPath(pathname)?.usesWorkingDate === true;
  return (
    <header className="sticky top-0 z-30 flex h-14 items-center gap-1 border-b border-separator bg-canvas px-2 sm:gap-3 sm:px-4 lg:h-16 lg:px-8 xl:px-10">
      <div className="flex shrink-0 items-center lg:hidden">
        <IconButton label="Меню розділів" icon={<Menu aria-hidden="true" className="size-5" />} size="lg" onClick={onOpenMenu} />
        <Wordmark to="/admin" compact className={cn(dated ? 'hidden sm:inline-flex' : 'inline-flex', 'px-1.5')} />
      </div>
      {dated ? <WorkingDateSwitcher /> : null}
      <div className="ms-auto flex shrink-0 items-center sm:gap-2">
        {/* стан звʼязку — у власному слоті; на телефоні це крапка-кнопка 44 px, і дата поруч лишається цілою */}
        <StandTimeBadge className={dated ? 'hidden sm:inline-flex' : undefined} />
        <LiveStatus state={live} className="shrink-0" />
        <IconButton
          label="Журнал дій"
          shortcut="mod+shift+h"
          size="lg"
          icon={<History aria-hidden="true" className="size-5" />}
          // на телефоні місце — даті; журнал там відкривається з палітри «Пошук і команди»
          className={dated ? 'hidden sm:inline-flex' : undefined}
          onClick={() => openJournal(true)}
        />
        <IconButton
          label="Пошук і команди"
          shortcut="mod+k"
          size="lg"
          icon={<Search aria-hidden="true" className="size-5" />}
          onClick={() => openPalette(true)}
        />
        {/* на телефоні користувач — унизу шторки з розділами, щоб у верхній панелі вмістилася дата */}
        <div className="hidden sm:block lg:hidden">
          <UserMenu />
        </div>
      </div>
    </header>
  );
}
