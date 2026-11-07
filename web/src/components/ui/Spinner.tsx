import { cn } from '@/lib/cn';

interface SpinnerProps {
  /** Текст для читачів екрана. */
  label?: string;
  size?: 'sm' | 'md' | 'lg';
  className?: string;
}

const SIZE = { sm: 'size-3.5', md: 'size-4', lg: 'size-6' } as const;

/** Кільце завантаження. Для вмісту краще Skeleton; Spinner — для кнопок і коротких дій. */
export function Spinner({ label = 'Завантаження', size = 'md', className }: SpinnerProps) {
  return (
    <span role="status" className={cn('inline-flex items-center', className)}>
      <svg
        viewBox="0 0 24 24"
        fill="none"
        aria-hidden="true"
        className={cn(SIZE[size], 'motion-safe:animate-spin')}
      >
        <circle cx="12" cy="12" r="9" stroke="currentColor" strokeOpacity="0.25" strokeWidth="3" />
        <path d="M21 12a9 9 0 0 0-9-9" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
      </svg>
      <span className="sr-only">{label}</span>
    </span>
  );
}
