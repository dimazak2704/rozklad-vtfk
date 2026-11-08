import { Search } from 'lucide-react';
import { cn } from '@/lib/cn';
import { useCommandStore } from '@/stores/commands';
import { KbdCombo } from '../ui/Kbd';
import { Tooltip } from '../ui/Tooltip';

interface SearchTriggerProps {
  tone?: 'default' | 'on-shell';
  label?: string;
  /** Клавіша Ctrl K у полі. За замовчуванням — лише в робочих панелях; на публічному сайті вона в підказці. */
  showShortcut?: boolean;
  className?: string;
}

/** Поле пошуку в шапці: відкриває палітру з пошуком розкладу (Ctrl/⌘+K або «/»). */
export function SearchTrigger({ tone = 'default', label = 'Група, викладач або аудиторія', showShortcut, className }: SearchTriggerProps) {
  const setOpen = useCommandStore((state) => state.setOpen);
  const onShell = tone === 'on-shell';
  const withKeys = showShortcut ?? !onShell;
  const button = (
    <button
      type="button"
      onClick={() => setOpen(true)}
      aria-label={`Пошук: ${label}`}
      aria-haspopup="dialog"
      className={cn(
        'flex h-9 w-full min-w-0 items-center gap-2 rounded-control border border-transparent px-3 text-start text-ui',
        'transition-[background-color,border-color] duration-100 ease-out focus-visible:outline-2 focus-visible:outline-offset-2',
        onShell
          ? 'bg-shell-raised text-on-shell-muted hover:bg-[color-mix(in_oklab,var(--background-color-shell)_84%,white)] focus-visible:outline-focus-on-shell'
          : 'bg-sunken text-muted hover:border-default focus-visible:outline-focus',
        className,
      )}
    >
      <Search aria-hidden="true" className="size-4 shrink-0" />
      <span className="min-w-0 flex-1 truncate">{label}</span>
      {withKeys ? <KbdCombo keys="mod+k" joined className="hidden md:inline-flex" /> : null}
    </button>
  );
  if (withKeys) return button;
  return (
    <Tooltip content="Пошук" shortcut="mod+k" side="bottom">
      {button}
    </Tooltip>
  );
}
