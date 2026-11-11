import { Menu, Search } from 'lucide-react';
import type { EventStreamState } from '@/api/useEventStream';
import { useCommandStore } from '@/stores/commands';
import { IconButton } from '../ui/IconButton';
import { LiveStatus } from './LiveStatus';
import { UserMenu } from './UserMenu';
import { Wordmark } from './Wordmark';
import { WorkingDateSwitcher } from './WorkingDateSwitcher';

interface AdminTopBarProps {
  live: EventStreamState;
  onOpenMenu: () => void;
}

/**
 * Верхня панель диспетчера — лише робоча дата і стан звʼязку (лише коли його немає) плюс пошук іконкою.
 * Користувач — унизу бічної панелі (і шторки на телефоні); на планшеті — тут, аватаром.
 */
export function AdminTopBar({ live, onOpenMenu }: AdminTopBarProps) {
  const openPalette = useCommandStore((state) => state.setOpen);
  return (
    <header className="sticky top-0 z-30 flex h-14 items-center gap-2 border-b border-separator bg-canvas px-2 sm:gap-3 sm:px-4 lg:h-16 lg:px-8 xl:px-10">
      <div className="flex shrink-0 items-center lg:hidden">
        <IconButton label="Меню розділів" icon={<Menu aria-hidden="true" className="size-5" />} size="lg" onClick={onOpenMenu} />
        <Wordmark to="/admin" compact className="hidden sm:inline-flex sm:px-1.5" />
      </div>
      <WorkingDateSwitcher />
      <div className="ms-auto flex shrink-0 items-center gap-1 sm:gap-2">
        <LiveStatus state={live} />
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
