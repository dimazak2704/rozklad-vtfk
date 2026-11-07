import { cn } from '@/lib/cn';

export type StatusTone = 'neutral' | 'live' | 'change' | 'cancel' | 'add' | 'room';

const TONES: Record<StatusTone, string> = {
  neutral: 'bg-[var(--fill-muted)]',
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
  /** Пульсація «наживо» (вимикається при зменшеному русі). */
  pulse?: boolean;
  className?: string;
}

/** Крапка стану: «наживо», «очікує», «скасовано». Підпис обовʼязковий — колір сам по собі нічого не каже. */
export function StatusDot({ tone = 'neutral', label, showLabel = false, pulse = false, className }: StatusDotProps) {
  return (
    <span className={cn('inline-flex items-center gap-1.5', className)}>
      <span
        aria-hidden="true"
        className={cn('relative inline-block size-2 shrink-0 rounded-full', TONES[tone], pulse && 'motion-safe:animate-live')}
      />
      <span className={showLabel ? 'text-caption text-secondary' : 'sr-only'}>{label}</span>
    </span>
  );
}
