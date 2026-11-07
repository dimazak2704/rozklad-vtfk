import { Search } from 'lucide-react';
import { cn } from '@/lib/cn';
import { useCommandStore } from '@/stores/commands';
import { KbdCombo } from '../ui/Kbd';

interface SearchTriggerProps {
  tone?: 'default' | 'on-shell';
  label?: string;
  className?: string;
}

/** Поле пошуку в шапці: відкриває палітру з пошуком розкладу (Ctrl/⌘+K або «/»). */
export function SearchTrigger({ tone = 'default', label = 'Група, викладач або аудиторія', className }: SearchTriggerProps) {
  const setOpen = useCommandStore((state) => state.setOpen);
  const onShell = tone === 'on-shell';
  return (
    <button
      type="button"
      onClick={() => setOpen(true)}
      aria-label={`Пошук: ${label}`}
      aria-haspopup="dialog"
      className={cn(
        'flex h-9 w-full min-w-0 items-center gap-2.5 rounded-control px-3 text-start text-ui transition-[background-color,border-color] duration-100',
        'focus-visible:outline-2 focus-visible:outline-offset-2',
        onShell
          ? 'border border-shell bg-shell-raised text-on-shell-muted hover:bg-shell-hover focus-visible:outline-focus-on-shell'
          : 'border border-control bg-surface text-muted hover:border-strong focus-visible:outline-focus',
        className,
      )}
    >
      <Search aria-hidden="true" className="size-4 shrink-0" />
      <span className="min-w-0 flex-1 truncate">{label}</span>
      <KbdCombo keys="mod+k" tone={onShell ? 'on-shell' : 'default'} className="hidden md:inline-flex" />
    </button>
  );
}
