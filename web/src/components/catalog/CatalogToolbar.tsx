import { Search, X } from 'lucide-react';
import { useRef, type ReactNode } from 'react';
import { Button, IconButton, Input, SegmentedControl } from '@/components/ui';
import { useHotkeys } from '@/hooks/useHotkeys';
import { countLabel } from '@/lib/labels';
import { ACTIVE_FILTER_OPTIONS, type ActiveFilter } from './labels';

interface CatalogToolbarProps {
  search: string;
  onSearch: (value: string) => void;
  /** «Назва аудиторії», «Прізвище або псевдонім». */
  placeholder: string;
  /** Підпис поля для читача екрана. */
  searchLabel: string;
  /** Фільтри довідника. */
  children?: ReactNode;
  total?: number;
  noun: readonly [string, string, string];
  filtered: boolean;
  onReset: () => void;
}

/** Рядок над таблицею: пошук (клавіша «/»), фільтри, скільки знайдено, «Скинути». */
export function CatalogToolbar({ search, onSearch, placeholder, searchLabel, children, total, noun, filtered, onReset }: CatalogToolbarProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  useHotkeys([
    {
      keys: '/',
      handler: (event) => {
        event.preventDefault();
        inputRef.current?.focus();
      },
      description: 'Пошук у таблиці',
      group: 'Таблиця',
    },
  ]);

  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
      <Input
        ref={inputRef}
        type="search"
        value={search}
        onChange={(event) => onSearch(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Escape' && search) {
            event.preventDefault();
            onSearch('');
          }
        }}
        placeholder={placeholder}
        aria-label={searchLabel}
        autoComplete="off"
        spellCheck={false}
        iconStart={<Search aria-hidden="true" className="size-4" />}
        endSlot={
          search ? (
            <IconButton label="Очистити пошук" icon={<X aria-hidden="true" className="size-3.5" />} size="sm" onClick={() => onSearch('')} />
          ) : undefined
        }
        wrapperClassName="w-full sm:w-72"
      />
      {children}
      <div className="ms-auto flex items-center gap-2">
        {total !== undefined ? (
          <p className="text-caption text-muted figures" aria-live="polite">
            {filtered ? 'Знайдено ' : ''}
            {countLabel(total, noun)}
          </p>
        ) : null}
        {filtered ? (
          <Button variant="ghost" size="sm" onClick={onReset}>
            Скинути
          </Button>
        ) : null}
      </div>
    </div>
  );
}

/** «Усі / Активні / Неактивні» — однаковий фільтр стану для всіх довідників. */
export function ActiveFilterControl({ value, onChange }: { value: ActiveFilter; onChange: (value: ActiveFilter) => void }) {
  return (
    <SegmentedControl<ActiveFilter>
      aria-label="Стан запису"
      size="sm"
      value={value}
      onValueChange={onChange}
      options={ACTIVE_FILTER_OPTIONS}
    />
  );
}
