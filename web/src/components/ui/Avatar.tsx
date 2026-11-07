import { cn } from '@/lib/cn';
import { initials } from '@/lib/names';

interface AvatarProps {
  name: string;
  size?: 'sm' | 'md' | 'lg';
  tone?: 'default' | 'on-shell';
  className?: string;
}

const SIZES = { sm: 'size-6 text-meta', md: 'size-8 text-caption', lg: 'size-10 text-ui' } as const;

/** Аватар з ініціалами. Фотографій викладачів система не зберігає. */
export function Avatar({ name, size = 'md', tone = 'default', className }: AvatarProps) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        'inline-flex shrink-0 items-center justify-center rounded-full font-condensed font-bold tracking-wide select-none',
        tone === 'default' ? 'bg-selected text-accent' : 'bg-shell-raised text-on-shell',
        SIZES[size],
        className,
      )}
    >
      {initials(name)}
    </span>
  );
}
