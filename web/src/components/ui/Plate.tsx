import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

interface PlateProps {
  children: ReactNode;
  /** `sm`/`md` — коди в списках, `lg` — помітний код у панелі, `xl` — код у заголовку сторінки (без рамки). */
  size?: 'sm' | 'md' | 'lg' | 'xl';
  /** `default` — табличка на поверхні, `accent` — лише вибрана сутність, `on-shell` — біла табличка в синій смузі. */
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
  'on-shell': 'border-transparent bg-plate text-plate',
} as const;

/**
 * Табличка з кодом — як номер маршруту на зупинці: «4КІ1», «ВТФК», «ауд. 309».
 * Для кодів груп, аудиторій і коротких ідентифікаторів, а не для довільного тексту.
 * У заголовку сторінки (`xl`) код стоїть сам, великим конденсованим, без рамки.
 */
export function Plate({ children, size = 'md', tone = 'default', className }: PlateProps) {
  return (
    <span
      className={cn(
        'inline-flex shrink-0 items-center font-condensed leading-none font-bold whitespace-nowrap figures',
        SIZES[size],
        size === 'xl' ? (tone === 'accent' ? 'text-accent' : tone === 'on-shell' ? 'text-on-shell' : 'text-primary') : ['rounded-plate border', TONES[tone]],
        className,
      )}
    >
      {children}
    </span>
  );
}
