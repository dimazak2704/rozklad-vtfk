import { Link } from 'react-router';
import { cn } from '@/lib/cn';
import { Tooltip } from '../ui/Tooltip';

interface BrandMarkProps {
  /** Розмір у px (квадрат). */
  size?: number;
  /** Знак малює себе один раз (екран входу). Без руху — одразу намальований. */
  draw?: boolean;
  className?: string;
}

/**
 * Знак — кільце й шпиль з емблеми коледжу, перемальовані однією лінією 1.5 px кольором `fill-brand`
 * (#4297D7, як на сайті коледжу). Лише графіка, ніколи не текст.
 */
export function BrandMark({ size = 26, draw = false, className }: BrandMarkProps) {
  const path = cn(draw && 'anim-mark-draw');
  return (
    <svg
      viewBox="0 0 26 26"
      width={size}
      height={size}
      aria-hidden="true"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.5}
      strokeLinejoin="round"
      className={cn('shrink-0 text-[var(--fill-brand)]', className)}
    >
      <circle className={path} pathLength={1} cx="13" cy="13" r="11.6" />
      <circle className={path} pathLength={1} cx="13" cy="8.9" r="5.2" />
      <path
        className={path}
        pathLength={1}
        d="M13 4.2C12.5 10.4 11 16 7.2 21.6 10.6 19.5 15.4 19.5 18.8 21.6 15 16 13.5 10.4 13 4.2Z"
      />
    </svg>
  );
}

interface WordmarkProps {
  to?: string;
  /** Лише знак (згорнута бічна панель, вузька шапка). */
  compact?: boolean;
  /** Застаріле: колишня синя смуга. Вигляд тепер один. */
  tone?: 'shell' | 'surface';
  /** Бік підказки «На головну»: у згорнутій бічній панелі — праворуч, як у її пунктів. */
  tooltipSide?: 'bottom' | 'right';
  className?: string;
}

/**
 * Словесний знак — один скрізь: знак-лінія і «Розклад ВТФК» (14/700). Посилання на головну свого розділу.
 */
export function Wordmark({ to = '/', compact = false, tooltipSide = 'bottom', className }: WordmarkProps) {
  return (
    <Tooltip content="На головну" side={tooltipSide} align="start">
      <Link
        to={to}
        aria-label="Розклад ВТФК — на головну"
        className={cn(
          'inline-flex min-h-10 shrink-0 items-center gap-2.5 rounded-control',
          'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
          compact && 'justify-center',
          className,
        )}
      >
        <BrandMark />
        {compact ? null : (
          <span aria-hidden="true" className="text-[0.9375rem] leading-5 font-bold tracking-[-0.005em] whitespace-nowrap text-primary">
            Розклад ВТФК
          </span>
        )}
      </Link>
    </Tooltip>
  );
}
