import { Menu, Search } from 'lucide-react';
import type { EventStreamState } from '@/api/useEventStream';
import { useCommandStore } from '@/stores/commands';
import { IconButton } from '../ui/IconButton';
import { LiveStatus } from './LiveStatus';
import { SearchTrigger } from './SearchTrigger';
import { ThemeToggle } from './ThemeToggle';
import { UserMenu } from './UserMenu';
import { WorkingDateSwitcher } from './WorkingDateSwitcher';

interface AdminTopBarProps {
  live: EventStreamState;
  onOpenMenu: () => void;
}

/**
 * Верхня панель диспетчера: робоча дата, пошук і команди, живі оновлення, тема, обліковий запис.
 * На вужчих екранах поле пошуку стає кнопкою, а підпис стану зʼєднання ховається в підказку.
 */
export function AdminTopBar({ live, onOpenMenu }: AdminTopBarProps) {
  const openPalette = useCommandStore((state) => state.setOpen);
  return (
    <header className="sticky top-0 z-30 flex h-14 items-center gap-2 border-b border-default bg-surface px-3 sm:gap-3 sm:px-5">
      <IconButton
        label="Меню розділів"
        icon={<Menu aria-hidden="true" className="size-5" />}
        className="lg:hidden"
        onClick={onOpenMenu}
      />
      <WorkingDateSwitcher />
      <div className="ms-auto flex shrink-0 items-center gap-1">
        <SearchTrigger label="Пошук і команди" className="hidden w-56 lg:flex xl:w-72" />
        <IconButton
          label="Пошук і команди"
          shortcut="mod+k"
          icon={<Search aria-hidden="true" className="size-[1.125rem]" />}
          className="lg:hidden"
          onClick={() => openPalette(true)}
        />
        <LiveStatus state={live} />
        <ThemeToggle />
        <UserMenu />
      </div>
    </header>
  );
}
