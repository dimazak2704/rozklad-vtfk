import { Check, ChevronDown, Moon, Sun, SunMoon } from 'lucide-react';
import { DropdownMenu } from 'radix-ui';
import { useResolvedTheme } from '@/hooks/useResolvedTheme';
import { cn } from '@/lib/cn';
import { useThemeStore, type ThemePreference } from '@/stores/theme';
import { IconButton } from '../ui/IconButton';
import { SegmentedControl } from '../ui/SegmentedControl';
import { THEME_OPTIONS as OPTIONS, useThemeLabel } from './theme-options';

const MENU =
  'anim-pop z-50 min-w-48 rounded-popover border border-default bg-raised p-1.5 text-primary shadow-popover';

const RADIO_ITEM = cn(
  'flex min-h-9 cursor-default items-center gap-2.5 rounded-control px-2.5 text-ui outline-none select-none pointer-coarse:min-h-11',
  'data-highlighted:bg-hover data-[state=checked]:font-[650]',
);

/** Пункти вибору теми для будь-якого меню Radix (меню користувача, підвал). Вибір запамʼятовується. */
export function ThemeRadioItems() {
  const preference = useThemeStore((state) => state.preference);
  const setPreference = useThemeStore((state) => state.setPreference);
  return (
    <DropdownMenu.RadioGroup value={preference} onValueChange={(value) => setPreference(value as ThemePreference)}>
      {OPTIONS.map((option) => (
        <DropdownMenu.RadioItem key={option.value} value={option.value} className={RADIO_ITEM}>
          <option.icon aria-hidden="true" className="size-4 text-muted" />
          <span className="flex-1">{option.label}</span>
          <DropdownMenu.ItemIndicator>
            <Check aria-hidden="true" className="size-4 text-accent" />
          </DropdownMenu.ItemIndicator>
        </DropdownMenu.RadioItem>
      ))}
    </DropdownMenu.RadioGroup>
  );
}

/** Вибір теми іконкою (робочі панелі, де немає меню користувача). */
export function ThemeToggle({ className }: { tone?: 'default' | 'on-shell'; className?: string }) {
  const preference = useThemeStore((state) => state.preference);
  const resolved = useResolvedTheme();
  const Icon = preference === 'system' ? SunMoon : resolved === 'dark' ? Moon : Sun;
  const current = useThemeLabel();

  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger asChild>
        <IconButton
          label={`Тема: ${current}`}
          icon={<Icon aria-hidden="true" className="size-[1.125rem]" />}
          tooltipSide="bottom"
          className={className}
        />
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content align="end" sideOffset={6} className={MENU}>
          <DropdownMenu.Label className="px-2.5 py-1.5 text-caption font-[650] text-muted">Тема</DropdownMenu.Label>
          <ThemeRadioItems />
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}

/**
 * «Тема: як у системі ▾» — тихе текстове меню для підвалу. Тема за замовчуванням іде за системою,
 * тож більшості людей цей вибір не потрібен; він не займає місця в шапці.
 */
export function ThemeMenu({ className }: { className?: string }) {
  const current = useThemeLabel();
  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger
        className={cn(
          'inline-flex min-h-8 items-center gap-1 rounded-control px-1.5 text-ui text-secondary transition-colors duration-150',
          'hover:text-primary data-[state=open]:text-primary pointer-coarse:min-h-11',
          'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
          className,
        )}
      >
        Тема: {current}
        <ChevronDown aria-hidden="true" className="size-4" />
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content align="end" side="top" sideOffset={6} className={MENU}>
          <ThemeRadioItems />
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}

/** Ті самі три варіанти перемикачем (сторінка компонентів, налаштування). У підвалі — `ThemeMenu`. */
export function ThemeSegmented({ className }: { className?: string }) {
  const preference = useThemeStore((state) => state.preference);
  const setPreference = useThemeStore((state) => state.setPreference);
  return (
    <SegmentedControl<ThemePreference>
      aria-label="Тема оформлення"
      value={preference}
      onValueChange={setPreference}
      size="sm"
      options={OPTIONS.map((option) => ({ value: option.value, label: option.label }))}
      className={className}
    />
  );
}
