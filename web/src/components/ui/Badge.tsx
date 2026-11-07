import { X } from 'lucide-react';
import type { ComponentProps, ReactNode } from 'react';
import { cn } from '@/lib/cn';

export type BadgeTone = 'neutral' | 'accent' | 'change' | 'cancel' | 'add' | 'room' | 'danger' | 'success' | 'warning';

/** Кольори тонів збігаються з мовою станів занять: бурштин — заміна, червоний — скасовано тощо. */
const TONES: Record<BadgeTone, string> = {
  neutral: 'border-default bg-sunken text-secondary',
  accent: 'border-accent-subtle bg-selected text-accent',
  change: 'border-change bg-change-subtle text-change',
  cancel: 'border-cancel bg-cancel-subtle text-cancel',
  add: 'border-add bg-add-subtle text-add',
  room: 'border-room bg-room-subtle text-room',
  danger: 'border-cancel bg-danger-subtle text-danger',
  success: 'border-add bg-add-subtle text-add',
  warning: 'border-change bg-change-subtle text-change',
};

export interface BadgeProps extends ComponentProps<'span'> {
  tone?: BadgeTone;
  icon?: ReactNode;
  size?: 'sm' | 'md';
}

/** Статична позначка: стан, причина («Відп.»), тип заняття. Колір завжди підкріплений текстом. */
export function Badge({ tone = 'neutral', icon, size = 'md', className, children, ...props }: BadgeProps) {
  return (
    <span
      className={cn(
        'inline-flex max-w-full items-center gap-1 rounded-plate border font-semibold whitespace-nowrap',
        size === 'sm' ? 'h-5 px-1.5 text-meta' : 'h-6 px-2 text-caption',
        TONES[tone],
        className,
      )}
      {...props}
    >
      {icon}
      <span className="truncate">{children}</span>
    </span>
  );
}

export interface ChipProps extends Omit<ComponentProps<'button'>, 'onClick'> {
  /** Вибраний фільтр. */
  selected?: boolean;
  onToggle?: () => void;
  /** Показати хрестик видалення. */
  onRemove?: () => void;
  removeLabel?: string;
  icon?: ReactNode;
  count?: number;
}

/** Інтерактивна «таблетка»: фільтр «Лише мої зміни», вибрана група. */
export function Chip({
  selected = false,
  onToggle,
  onRemove,
  removeLabel = 'Прибрати',
  icon,
  count,
  className,
  children,
  disabled,
  ...props
}: ChipProps) {
  return (
    <span
      className={cn(
        'inline-flex h-8 items-stretch overflow-hidden rounded-full border text-ui font-semibold',
        selected ? 'border-accent bg-selected text-accent' : 'border-default bg-surface text-secondary',
        disabled && 'opacity-50',
        className,
      )}
    >
      <button
        type="button"
        aria-pressed={onToggle ? selected : undefined}
        onClick={onToggle}
        disabled={disabled}
        className={cn(
          'inline-flex items-center gap-1.5 ps-3 transition-[background-color,color] duration-100 ease-out',
          onRemove ? 'pe-1.5' : 'pe-3',
          selected ? 'hover:bg-selected-hover' : 'hover:bg-hover hover:text-primary',
          'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus disabled:cursor-not-allowed',
        )}
        {...props}
      >
        {icon}
        {children}
        {count !== undefined ? <span className="figures text-caption opacity-80">{count}</span> : null}
      </button>
      {onRemove ? (
        <button
          type="button"
          aria-label={removeLabel}
          onClick={onRemove}
          disabled={disabled}
          className="inline-flex w-7 items-center justify-center pe-1 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus"
        >
          <X aria-hidden="true" className="size-3.5" />
        </button>
      ) : null}
    </span>
  );
}
