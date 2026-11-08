import { Menu, Search } from 'lucide-react';
import type { EventStreamState } from '@/api/useEventStream';
import { useCommandStore } from '@/stores/commands';
import { IconButton } from '../ui/IconButton';
import { LiveStatus } from './LiveStatus';
import { SearchTrigger } from './SearchTrigger';
import { UserMenu } from './UserMenu';
import { Wordmark } from './Wordmark';
import { WorkingDateSwitcher } from './WorkingDateSwitcher';

interface AdminTopBarProps {
  live: EventStreamState;
  onOpenMenu: () => void;
}

/**
 * Верхня панель диспетчера — біла, як і бічна (одна L-рамка): робоча дата з парністю, пошук і команди,
 * стан живих оновлень (лише коли щось не так) і обліковий запис з темою. Відступи збігаються з h1 сторінки.
 */
export function AdminTopBar({ live, onOpenMenu }: AdminTopBarProps) {
  const openPalette = useCommandStore((state) => state.setOpen);
  return (
    <header className="sticky top-0 z-30 flex h-14 items-center gap-2 border-b border-default bg-surface px-3 sm:gap-3 sm:px-5 lg:px-8">
      <div className="flex shrink-0 items-center gap-1 lg:hidden">
        <IconButton label="Меню розділів" icon={<Menu aria-hidden="true" className="size-5" />} size="lg" className="-ms-2" onClick={onOpenMenu} />
        <Wordmark to="/admin" tone="surface" className="me-2 hidden sm:inline-flex" />
        <Wordmark to="/admin" tone="surface" compact className="me-1 sm:hidden" />
      </div>
      <WorkingDateSwitcher />
      <div className="ms-auto flex shrink-0 items-center gap-2">
        <LiveStatus state={live} />
        <SearchTrigger label="Пошук або команда…" className="hidden w-56 lg:flex xl:w-72" />
        <IconButton
          label="Пошук і команди"
          shortcut="mod+k"
          icon={<Search aria-hidden="true" className="size-[1.125rem]" />}
          className="lg:hidden"
          onClick={() => openPalette(true)}
        />
        <UserMenu />
      </div>
    </header>
  );
}
