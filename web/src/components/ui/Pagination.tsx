import { ChevronLeft, ChevronRight } from 'lucide-react';
import { cn } from '@/lib/cn';
import { pageWindow } from '@/lib/pagination';
import { IconButton } from './IconButton';

interface PaginationProps {
  /** Номер сторінки з нуля (як у Page<T> з API). */
  page: number;
  size: number;
  total: number;
  onPageChange: (page: number) => void;
  /** Назва того, що гортаємо: «викладачів», «розпоряджень». */
  noun?: string;
  className?: string;
}

/** Посторінкова навігація під таблицею: «1–20 з 134» і номери сторінок. */
export function Pagination({ page, size, total, onPageChange, noun = 'записів', className }: PaginationProps) {
  const count = Math.max(1, Math.ceil(total / Math.max(1, size)));
  const from = total === 0 ? 0 : page * size + 1;
  const to = Math.min(total, (page + 1) * size);

  return (
    <nav aria-label="Сторінки" className={cn('flex flex-wrap items-center justify-between gap-3', className)}>
      <p className="text-caption text-muted figures" aria-live="polite">
        {total === 0 ? `Немає ${noun}` : `${from}–${to} з ${total} ${noun}`}
      </p>
      {count > 1 ? (
        <div className="flex items-center gap-1">
          <IconButton
            label="Попередня сторінка"
            icon={<ChevronLeft aria-hidden="true" className="size-4 rtl:rotate-180" />}
            size="sm"
            disabled={page <= 0}
            onClick={() => onPageChange(page - 1)}
          />
          <ul className="hidden items-center gap-1 sm:flex">
            {pageWindow(page, count).map((item, index) =>
              item === 'gap' ? (
                <li key={`gap-${index}`} className="px-1 text-muted" aria-hidden="true">
                  …
                </li>
              ) : (
                <li key={item}>
                  <button
                    type="button"
                    aria-current={item === page ? 'page' : undefined}
                    aria-label={`Сторінка ${item + 1}`}
                    onClick={() => onPageChange(item)}
                    className={cn(
                      'h-8 min-w-8 rounded-control px-2 text-caption font-semibold figures',
                      'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
                      item === page ? 'bg-selected text-accent' : 'text-secondary hover:bg-hover hover:text-primary',
                    )}
                  >
                    {item + 1}
                  </button>
                </li>
              ),
            )}
          </ul>
          <span className="px-2 text-caption text-secondary figures sm:hidden">
            {page + 1} / {count}
          </span>
          <IconButton
            label="Наступна сторінка"
            icon={<ChevronRight aria-hidden="true" className="size-4 rtl:rotate-180" />}
            size="sm"
            disabled={page >= count - 1}
            onClick={() => onPageChange(page + 1)}
          />
        </div>
      ) : null}
    </nav>
  );
}
