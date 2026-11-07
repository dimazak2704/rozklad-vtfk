import { ChevronRight } from 'lucide-react';
import { Link } from 'react-router';
import { cn } from '@/lib/cn';

export interface Crumb {
  label: string;
  /** Без `to` — поточна сторінка. */
  to?: string;
}

interface BreadcrumbsProps {
  items: readonly Crumb[];
  className?: string;
}

/** Навігаційний ланцюжок: «Адмінка › Каталог › Викладачі». Останній елемент — поточна сторінка. */
export function Breadcrumbs({ items, className }: BreadcrumbsProps) {
  if (items.length === 0) return null;
  return (
    <nav aria-label="Навігаційний ланцюжок" className={cn('min-w-0', className)}>
      <ol className="flex flex-wrap items-center gap-1 text-caption text-muted">
        {items.map((item, index) => {
          const last = index === items.length - 1;
          return (
            <li key={`${item.label}-${index}`} className="flex min-w-0 items-center gap-1">
              {item.to && !last ? (
                <Link
                  to={item.to}
                  className="truncate rounded-plate hover:text-primary hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus"
                >
                  {item.label}
                </Link>
              ) : (
                <span aria-current={last ? 'page' : undefined} className={cn('truncate', last && 'text-secondary')}>
                  {item.label}
                </span>
              )}
              {!last ? <ChevronRight aria-hidden="true" className="size-3.5 shrink-0 rtl:rotate-180" /> : null}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
