import { Command } from 'cmdk';
import { Check, ChevronsUpDown, Search, X } from 'lucide-react';
import { useState, type ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { matchScore } from '@/lib/search';
import { useFieldControl } from './field-context';
import { Popover, PopoverContent, PopoverTrigger } from './Popover';
import { Spinner } from './Spinner';
import { CONTROL_BASE, CONTROL_SIZE, type ControlSize } from './styles';

export interface ComboboxOption {
  value: string;
  label: string;
  /** Додаткові рядки для пошуку: повне імʼя, псевдоніми, код. */
  keywords?: string[];
  description?: string;
  disabled?: boolean;
  icon?: ReactNode;
}

export interface ComboboxProps {
  value: string | null;
  onValueChange: (value: string | null) => void;
  options: readonly ComboboxOption[];
  placeholder?: string;
  searchPlaceholder?: string;
  emptyText?: string;
  /** Підвантаження варіантів з сервера: показує спінер у списку. */
  loading?: boolean;
  /** Для віддаленого пошуку: отримує текст запиту. Тоді локальна фільтрація вимикається. */
  onSearchChange?: (query: string) => void;
  /** Показати кнопку очищення. */
  clearable?: boolean;
  size?: ControlSize;
  disabled?: boolean;
  id?: string;
  'aria-label'?: string;
  'aria-describedby'?: string;
  'aria-invalid'?: boolean;
  className?: string;
}

/**
 * Вибір із довгого списку з пошуком: групи, викладачі, аудиторії, дисципліни.
 * Пошук стійкий до латиниці й одруківок (`4ki1` → 4КІ1, `кренц` → Кренцін).
 */
export function Combobox({
  value,
  onValueChange,
  options,
  placeholder = 'Оберіть…',
  searchPlaceholder = 'Пошук…',
  emptyText = 'Нічого не знайдено. Спробуйте інше написання.',
  loading = false,
  onSearchChange,
  clearable = false,
  size = 'md',
  disabled,
  className,
  'aria-label': ariaLabel,
  ...rest
}: ComboboxProps) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const a11y = useFieldControl({ ...rest, disabled });
  const selected = options.find((option) => option.value === value) ?? null;
  const remote = Boolean(onSearchChange);

  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) setQuery('');
      }}
    >
      <div className={cn('relative w-full', className)}>
        <PopoverTrigger asChild disabled={a11y.disabled}>
          <button
            type="button"
            role="combobox"
            aria-expanded={open}
            aria-haspopup="listbox"
            aria-label={ariaLabel}
            id={a11y.id}
            aria-describedby={a11y['aria-describedby']}
            aria-invalid={a11y['aria-invalid']}
            disabled={a11y.disabled}
            className={cn(
              CONTROL_BASE,
              CONTROL_SIZE[size],
              'flex items-center justify-between gap-2 text-start',
              clearable && selected && 'pe-16',
            )}
          >
            <span className={cn('flex min-w-0 items-center gap-2 truncate', !selected && 'text-muted')}>
              {selected?.icon}
              <span className="truncate">{selected ? selected.label : placeholder}</span>
            </span>
            <ChevronsUpDown aria-hidden="true" className="size-4 shrink-0 text-muted" />
          </button>
        </PopoverTrigger>
        {clearable && selected && !a11y.disabled ? (
          <button
            type="button"
            aria-label="Очистити вибір"
            onClick={() => onValueChange(null)}
            className="absolute inset-y-0 end-8 my-auto flex size-6 items-center justify-center rounded-plate text-muted hover:bg-hover hover:text-primary focus-visible:outline-2 focus-visible:outline-focus"
          >
            <X aria-hidden="true" className="size-3.5" />
          </button>
        ) : null}
      </div>
      <PopoverContent width="trigger" className="min-w-64 p-0">
        <Command
          shouldFilter={!remote}
          filter={(_value, search, keywords) => matchScore(search, ...(keywords ?? [])) / 100}
          label={ariaLabel ?? placeholder}
        >
          <div className="flex items-center gap-2 border-b border-separator px-3">
            <Search aria-hidden="true" className="size-4 text-muted" />
            <Command.Input
              value={query}
              onValueChange={(next) => {
                setQuery(next);
                onSearchChange?.(next);
              }}
              placeholder={searchPlaceholder}
              className="h-10 w-full bg-transparent text-body text-primary outline-none placeholder:text-muted sm:text-ui"
            />
          </div>
          <Command.List className="max-h-72 overflow-y-auto overscroll-contain p-1">
            {loading ? (
              <Command.Loading>
                <div className="flex items-center gap-2 px-3 py-2 text-caption text-muted">
                  <Spinner size="sm" /> Шукаємо…
                </div>
              </Command.Loading>
            ) : null}
            <Command.Empty className="px-3 py-6 text-center text-caption text-muted">{emptyText}</Command.Empty>
            {options.map((option) => (
              <Command.Item
                key={option.value}
                value={option.value}
                keywords={[option.label, ...(option.keywords ?? [])]}
                disabled={option.disabled}
                onSelect={() => {
                  onValueChange(option.value);
                  setOpen(false);
                  setQuery('');
                }}
                className={cn(
                  'flex cursor-default select-none items-center gap-2 rounded-control px-2 py-2 text-ui outline-none',
                  'data-[selected=true]:bg-hover data-[disabled=true]:text-disabled',
                )}
              >
                <Check
                  aria-hidden="true"
                  className={cn('size-4 text-accent', option.value === value ? 'opacity-100' : 'opacity-0')}
                />
                {option.icon ? <span className="text-muted">{option.icon}</span> : null}
                <span className="flex min-w-0 flex-col">
                  <span className="truncate">{option.label}</span>
                  {option.description ? (
                    <span className="truncate text-caption text-muted">{option.description}</span>
                  ) : null}
                </span>
              </Command.Item>
            ))}
          </Command.List>
        </Command>
      </PopoverContent>
    </Popover>
  );
}
