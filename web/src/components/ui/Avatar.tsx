import { cn } from '@/lib/cn';
import { initials } from '@/lib/names';

interface AvatarProps {
  name: string;
  size?: 'sm' | 'md' | 'lg';
  tone?: 'default' | 'on-shell';
  className?: string;
}

const SIZES = { sm: 'size-6 text-meta', md: 'size-8 text-caption', lg: 'size-10 text-ui' } as const;

/**
 * Аватар з ініціалами. Фотографій викладачів система не зберігає. Нейтральний: колір у системі
 * зарезервований для станів і «живого», тож людина не отримує випадкового відтінку.
 */
export function Avatar({ name, size = 'md', tone = 'default', className }: AvatarProps) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        'inline-flex shrink-0 items-center justify-center rounded-full font-condensed leading-none font-bold select-none',
        tone === 'default' ? 'bg-active text-secondary' : 'text-on-shell ring-1 ring-[var(--text-color-on-shell-muted)] ring-inset',
        SIZES[size],
        className,
      )}
    >
      {/* «Розробник (dev)» → «Р»: пояснення в дужках ініціалів не дає */}
      {initials(name.replace(/\s*\([^)]*\)/g, '').trim() || name)}
    </span>
  );
}
