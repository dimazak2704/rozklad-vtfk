import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

interface PlateProps {
  children: ReactNode;
  size?: 'sm' | 'md' | 'lg' | 'xl';
  /** `default` — емальована табличка на поверхні, `accent` — вибрана, `on-shell` — у синій смузі. */
  tone?: 'default' | 'accent' | 'on-shell';
  className?: string;
}

const SIZES = {
  sm: 'h-5 px-1.5 text-meta',
  md: 'h-6 px-2 text-caption',
  lg: 'h-9 px-2.5 text-heading',
  xl: 'h-14 px-3.5 text-display',
} as const;

const TONES = {
  default: 'border-strong bg-surface text-primary',
  accent: 'border-accent bg-selected text-accent',
  'on-shell': 'border-transparent bg-plate text-plate',
} as const;

/**
 * Табличка з кодом — як номер маршруту на зупинці: «4КІ1», «ВТФК», «ауд. 309».
 * Для кодів груп, аудиторій і коротких ідентифікаторів, а не для довільного тексту.
 */
export function Plate({ children, size = 'md', tone = 'default', className }: PlateProps) {
  return (
    <span
      className={cn(
        'inline-flex shrink-0 items-center rounded-plate border font-condensed leading-none font-extrabold tracking-[0.02em] whitespace-nowrap figures',
        SIZES[size],
        TONES[tone],
        className,
      )}
    >
      {children}
    </span>
  );
}
