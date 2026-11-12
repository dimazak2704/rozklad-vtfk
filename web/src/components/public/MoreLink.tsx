import { ArrowRight } from 'lucide-react';
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { cn } from '@/lib/cn';

/**
 * «Усі заміни →», «Тиждень →» — тихе посилання на наступний рівень; ціль 44 px на дотику. Мишею під текстом
 * проїжджає риска, а стрілка зсувається на 3 px (interaction.css).
 */
export function MoreLink({ to, children, className }: { to: string; children: ReactNode; className?: string }) {
  return (
    <Link
      to={to}
      className={cn(
        'inline-flex min-h-8 w-fit items-center gap-1 rounded-tag text-ui font-[650] text-accent pointer-coarse:min-h-11',
        'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
        className,
      )}
    >
      <span className="u-line">{children}</span>
      <ArrowRight aria-hidden="true" className="more-arrow size-4 rtl:rotate-180" />
    </Link>
  );
}
