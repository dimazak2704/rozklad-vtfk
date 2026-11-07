import { Fragment, type ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { keyLabel } from '@/lib/keys';

interface KbdProps {
  children: ReactNode;
  className?: string;
  tone?: 'default' | 'on-shell' | 'inverse';
}

/** Одна клавіша. */
export function Kbd({ children, className, tone = 'default' }: KbdProps) {
  return (
    <kbd
      className={cn(
        'inline-flex h-5 min-w-5 items-center justify-center rounded-plate px-1 font-sans text-meta font-semibold figures',
        tone === 'default' && 'border border-default bg-sunken text-secondary',
        tone === 'on-shell' && 'border border-shell bg-shell-raised text-on-shell-muted',
        tone === 'inverse' && 'border border-[color-mix(in_oklab,currentColor_35%,transparent)] text-inverse',
        className,
      )}
    >
      {children}
    </kbd>
  );
}

interface KbdComboProps {
  /** `mod+k`, `shift+?`, `arrowleft` */
  keys: string;
  className?: string;
  tone?: KbdProps['tone'];
}

/** Комбінація клавіш з урахуванням платформи. */
export function KbdCombo({ keys, className, tone }: KbdComboProps) {
  const parts = keys.split('+').filter(Boolean);
  return (
    <span className={cn('inline-flex items-center gap-0.5', className)} aria-label={parts.map(keyLabel).join(' + ')}>
      {parts.map((part, index) => (
        <Fragment key={`${part}-${index}`}>
          <Kbd tone={tone}>{keyLabel(part)}</Kbd>
        </Fragment>
      ))}
    </span>
  );
}
