import { Fragment, type ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { IS_MAC, keyLabel } from '@/lib/keys';

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
        'inline-flex h-5 min-w-5 items-center justify-center rounded-plate px-1 font-sans text-meta font-medium figures',
        tone === 'default' && 'border border-separator bg-sunken text-secondary',
        tone === 'on-shell' && 'bg-shell-hover text-on-shell-muted',
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
  /** Одна табличка на всю комбінацію («Ctrl K») замість окремих клавіш. */
  joined?: boolean;
}

/** Комбінація клавіш з урахуванням платформи. */
export function KbdCombo({ keys, className, tone, joined = false }: KbdComboProps) {
  const parts = keys.split('+').filter(Boolean);
  const label = parts.map(keyLabel).join(' + ');
  if (joined) {
    return (
      <span className={cn('inline-flex', className)} aria-label={label}>
        <Kbd tone={tone} className="px-1.5">
          {parts.map(keyLabel).join(IS_MAC ? '' : ' ')}
        </Kbd>
      </span>
    );
  }
  return (
    <span className={cn('inline-flex items-center gap-0.5', className)} aria-label={label}>
      {parts.map((part, index) => (
        <Fragment key={`${part}-${index}`}>
          <Kbd tone={tone}>{keyLabel(part)}</Kbd>
        </Fragment>
      ))}
    </span>
  );
}
