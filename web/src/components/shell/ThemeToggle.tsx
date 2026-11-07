import { Monitor, Moon, Sun } from 'lucide-react';
import { DropdownMenu } from 'radix-ui';
import { useResolvedTheme } from '@/hooks/useResolvedTheme';
import { cn } from '@/lib/cn';
import { useThemeStore, type ThemePreference } from '@/stores/theme';
import { IconButton } from '../ui/IconButton';

const OPTIONS: Array<{ value: ThemePreference; label: string; icon: typeof Sun }> = [
  { value: 'light', label: 'Світла', icon: Sun },
  { value: 'dark', label: 'Темна', icon: Moon },
  { value: 'system', label: 'Як у системі', icon: Monitor },
];

interface ThemeToggleProps {
  tone?: 'default' | 'on-shell';
}

/** Вибір теми: світла, темна або системна. Вибір запамʼятовується. */
export function ThemeToggle({ tone = 'default' }: ThemeToggleProps) {
  const preference = useThemeStore((state) => state.preference);
  const setPreference = useThemeStore((state) => state.setPreference);
  const resolved = useResolvedTheme();
  const Icon = preference === 'system' ? Monitor : resolved === 'dark' ? Moon : Sun;
  const current = OPTIONS.find((o) => o.value === preference)?.label.toLocaleLowerCase('uk') ?? '';

  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger asChild>
        <IconButton
          label={`Тема: ${current}`}
          icon={<Icon aria-hidden="true" className="size-[1.125rem]" />}
          variant={tone === 'on-shell' ? 'shell' : 'ghost'}
        />
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content
          align="end"
          sideOffset={6}
          className="anim-pop z-50 min-w-44 rounded-panel border border-default bg-raised p-1 text-primary shadow-popover"
        >
          <DropdownMenu.Label className="px-2.5 py-1.5 text-caption font-semibold text-muted">Тема оформлення</DropdownMenu.Label>
          <DropdownMenu.RadioGroup value={preference} onValueChange={(value) => setPreference(value as ThemePreference)}>
            {OPTIONS.map((option) => (
              <DropdownMenu.RadioItem
                key={option.value}
                value={option.value}
                className={cn(
                  'flex cursor-default select-none items-center gap-2.5 rounded-control px-2.5 py-2 text-ui outline-none',
                  'data-highlighted:bg-hover data-[state=checked]:font-semibold data-[state=checked]:text-accent',
                )}
              >
                <option.icon aria-hidden="true" className="size-4" />
                {option.label}
              </DropdownMenu.RadioItem>
            ))}
          </DropdownMenu.RadioGroup>
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}
