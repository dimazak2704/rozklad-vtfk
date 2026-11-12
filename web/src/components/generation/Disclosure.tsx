import { ChevronRight } from 'lucide-react';
import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

interface DisclosureProps {
  /** Що відкриється: «Що означають ваги?», «Хід розвʼязання». */
  summary: ReactNode;
  children: ReactNode;
  defaultOpen?: boolean;
  className?: string;
  /** Класи вмісту під заголовком. */
  contentClassName?: string;
}

/**
 * Тиха розгортка для другорядного: подробиці, пояснення, довгі списки. Нативний `<details>` — працює з
 * клавіатури й читачем екрана без JS; ціль 44 px на дотику.
 * Кандидат у `@/components/ui` — поки живе тут, бо ним користуються лише сторінки планування.
 */
export function Disclosure({ summary, children, defaultOpen = false, className, contentClassName }: DisclosureProps) {
  return (
    <details className={cn('group/disclosure flex flex-col', className)} open={defaultOpen || undefined}>
      <summary
        className={cn(
          'flex min-h-9 w-fit cursor-pointer list-none items-center gap-1.5 rounded-control text-ui font-[650] text-secondary select-none hover-fine:text-primary',
          'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus pointer-coarse:min-h-11 [&::-webkit-details-marker]:hidden',
        )}
      >
        <ChevronRight
          aria-hidden="true"
          className="size-4 shrink-0 transition-transform duration-150 group-open/disclosure:rotate-90 motion-reduce:transition-none rtl:rotate-180 rtl:group-open/disclosure:rotate-90"
        />
        {summary}
      </summary>
      <div className={cn('pt-2', contentClassName)}>{children}</div>
    </details>
  );
}
