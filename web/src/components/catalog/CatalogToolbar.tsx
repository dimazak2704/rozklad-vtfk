import { ListFilter, Search, X } from 'lucide-react';
import { useRef, type ReactNode } from 'react';
import { Button, IconButton, Input, Popover, PopoverContent, PopoverTrigger, SegmentedControl } from '@/components/ui';
import { handleSearchEscape, useHotkeys } from '@/hooks/useHotkeys';
import { countLabel } from '@/lib/labels';
import { ACTIVE_FILTER_OPTIONS, type ActiveFilter } from './labels';

interface CatalogToolbarProps {
  search: string;
  onSearch: (value: string) => void;
  /** «Назва аудиторії», «Прізвище або псевдонім». */
  placeholder: string;
  /** Підпис поля для читача екрана. */
  searchLabel: string;
  /** Фільтри довідника — у спливній панелі «Фільтр», кожен у `FilterField`. */
  children?: ReactNode;
  /** Скільки фільтрів (крім пошуку) увімкнено — число на кнопці «Фільтр». */
  activeFilters?: number;
  total?: number;
  noun: readonly [string, string, string];
  filtered: boolean;
  onReset: () => void;
}

/**
 * Рядок над таблицею: пошук (клавіша «/») і одна кнопка «Фільтр» замість ряду селектів і перемикачів.
 * Скільки знайдено — лише коли щось відфільтровано, разом з «Показати всі».
 */
export function CatalogToolbar({
  search,
  onSearch,
  placeholder,
  searchLabel,
  children,
  activeFilters = 0,
  total,
  noun,
  filtered,
  onReset,
}: CatalogToolbarProps) {
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
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
        <Input
          ref={inputRef}
          type="search"
          value={search}
          onChange={(event) => onSearch(event.target.value)}
          onKeyDown={(event) => handleSearchEscape(event, search, () => onSearch(''))}
          placeholder={placeholder}
          aria-label={searchLabel}
          autoComplete="off"
          spellCheck={false}
          iconStart={<Search aria-hidden="true" className="size-4" />}
          endSlot={
            search ? <IconButton label="Очистити пошук" icon={<X aria-hidden="true" className="size-3.5" />} size="sm" onClick={() => onSearch('')} /> : undefined
          }
          wrapperClassName="min-w-0 flex-1 sm:w-72 sm:flex-none"
        />
        {children ? (
          <Popover>
            <PopoverTrigger asChild>
              {/* на вузькому телефоні (< 400 px) — іконка з підписом для читача екрана: пошуку лишається місце */}
              <Button
                iconStart={<ListFilter aria-hidden="true" className="size-4" />}
                aria-label={activeFilters > 0 ? `Фільтр: увімкнено ${activeFilters}` : 'Фільтр'}
                className="max-[399.98px]:w-11 max-[399.98px]:gap-0 max-[399.98px]:px-0"
              >
                <span className="max-[399.98px]:sr-only">Фільтр</span>
                {activeFilters > 0 ? (
                  <span
                    aria-hidden="true"
                    className="ms-0.5 inline-flex size-5 items-center justify-center rounded-full bg-accent text-meta text-on-accent figures max-[399.98px]:absolute max-[399.98px]:-end-1.5 max-[399.98px]:-top-1.5"
                  >
                    {activeFilters}
                  </span>
                ) : null}
              </Button>
            </PopoverTrigger>
            <PopoverContent title="Що показати" width="md">
              <div className="flex flex-col gap-4">{children}</div>
            </PopoverContent>
          </Popover>
        ) : null}
      </div>
      {filtered ? (
        <p className="flex flex-wrap items-center gap-x-3 text-caption text-muted" aria-live="polite">
          {total !== undefined ? <span className="figures">Знайдено {countLabel(total, noun)}</span> : null}
          <button
            type="button"
            onClick={onReset}
            className="rounded-tag font-[650] text-accent hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus pointer-coarse:min-h-11"
          >
            Показати всі
          </button>
        </p>
      ) : null}
    </div>
  );
}

/** Підписане поле у спливній панелі фільтра. */
export function FilterField({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <span aria-hidden="true" className="text-caption font-[650] text-primary">
        {label}
      </span>
      {children}
    </div>
  );
}

/** «Усі / Активні / Неактивні» — однаковий фільтр стану для всіх довідників. */
export function ActiveFilterControl({ value, onChange }: { value: ActiveFilter; onChange: (value: ActiveFilter) => void }) {
  return <SegmentedControl<ActiveFilter> aria-label="Стан запису" stretch value={value} onValueChange={onChange} options={ACTIVE_FILTER_OPTIONS} />;
}
