import { cn } from '@/lib/cn';

export type StatusTone = 'neutral' | 'live' | 'change' | 'cancel' | 'add' | 'room';

/** Нейтральний стан («очікує») — порожнє кільце, решта — заповнена крапка свого чорнила. */
const DOT: Record<StatusTone, string> = {
  neutral: 'border-[1.5px] border-[var(--fill-muted)] bg-transparent',
  live: 'bg-[var(--fill-accent)]',
  change: 'bg-[var(--fill-change)]',
  cancel: 'bg-[var(--fill-cancel)]',
  add: 'bg-[var(--fill-add)]',
  room: 'bg-[var(--fill-room)]',
};


interface StatusDotProps {
  tone?: StatusTone;
  /** Видимий підпис поруч; без нього — лише для читача екрана. */
  label: string;
  showLabel?: boolean;
  /**
   * Залишено для сумісності: крапка стану завжди статична. Єдина пульсація на екрані — маркер «зараз»
   * (утиліта `live-ring`).
   */
  pulse?: boolean;
  className?: string;
}

/**
 * Крапка 8 px + слово: «● Підключено». Колір несе лише крапка, слово — звичайним текстом (один носій кольору).
 * Підпис обовʼязковий — колір сам по собі нічого не каже.
 */
export function StatusDot({ tone = 'neutral', label, showLabel = false, className }: StatusDotProps) {
  return (
    <span className={cn('inline-flex items-center gap-1.5', className)}>
      <span aria-hidden="true" className={cn('inline-block size-2 shrink-0 rounded-full', DOT[tone])} />
      <span className={showLabel ? 'text-ui text-primary' : 'sr-only'}>{label}</span>
    </span>
  );
}
