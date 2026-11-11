import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

interface PlateProps {
  children: ReactNode;
  /** `sm`/`md` — коди в списках, `lg` — помітний код у панелі, `xl` — код у заголовку сторінки (без рамки). */
  size?: 'sm' | 'md' | 'lg' | 'xl';
  /** `default` — код на поверхні, `accent` — лише вибрана сутність; `on-shell` — застаріле, = default. */
  tone?: 'default' | 'accent' | 'on-shell';
  className?: string;
}

const SIZES = {
  sm: 'h-5 px-1.5 text-meta',
  md: 'h-6 px-1.5 text-ui',
  lg: 'h-8 px-2 text-heading',
  xl: 'px-0 text-display',
} as const;

const TONES = {
  default: 'border-default bg-surface text-primary',
  accent: 'border-accent bg-selected text-accent',
  'on-shell': 'border-default bg-surface text-primary',
} as const;

/**
 * Код сутності: «4КІ1», «ауд. 309».
 * Для кодів груп, аудиторій і коротких ідентифікаторів, а не для довільного тексту.
 * У заголовку сторінки (`xl`) код стоїть сам, великим конденсованим, без рамки.
 */
export function Plate({ children, size = 'md', tone = 'default', className }: PlateProps) {
  return (
    <span
      className={cn(
        // Geologica 600 з табличними цифрами; +0.01em, щоб «І1» у «4КІ1» не злипалось
        'inline-flex shrink-0 items-center font-display leading-none font-semibold tracking-[0.01em] whitespace-nowrap tabular-nums',
        SIZES[size],
        size === 'xl' ? (tone === 'accent' ? 'text-accent' : 'text-primary') : ['rounded-tag border', TONES[tone]],
        className,
      )}
    >
      {children}
    </span>
  );
}
