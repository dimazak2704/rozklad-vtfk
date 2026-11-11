import { Search } from 'lucide-react';
import { cn } from '@/lib/cn';
import { useCommandStore } from '@/stores/commands';
import { KbdCombo } from '../ui/Kbd';

interface SearchTriggerProps {
  /** Застаріле: колишня синя смуга. */
  tone?: 'default' | 'on-shell';
  label?: string;
  /** Клавіша Ctrl K у полі (за замовчуванням так, від 1024 px). */
  showShortcut?: boolean;
  className?: string;
}

/** Тихе «поле» пошуку в шапці: відкриває палітру з пошуком розкладу (Ctrl/⌘+K або «/»). */
export function SearchTrigger({ label = 'Група, викладач, аудиторія', showShortcut = true, className }: SearchTriggerProps) {
  const setOpen = useCommandStore((state) => state.setOpen);
  return (
    <button
      type="button"
      onClick={() => setOpen(true)}
      aria-label={`Пошук: ${label}`}
      aria-haspopup="dialog"
      className={cn(
        'flex h-10 w-full min-w-0 items-center gap-2 rounded-control border border-transparent bg-sunken px-3 text-start text-ui text-muted',
        'transition-[background-color,border-color] duration-150 ease-out hover:border-default',
        'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
        className,
      )}
    >
      <Search aria-hidden="true" className="size-4 shrink-0" />
      <span className="min-w-0 flex-1 truncate">{label}</span>
      {showShortcut ? <KbdCombo keys="mod+k" joined className="hidden lg:inline-flex" /> : null}
    </button>
  );
}
